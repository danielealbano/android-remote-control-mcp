#!/usr/bin/env bun
import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { readFileSync, chmodSync, existsSync } from "fs";
import { join } from "path";
import { timingSafeEqual } from "crypto";
import { homedir } from "os";
import { parseEnvLines } from "./env.ts";

// --- Config loading ---
const STATE_DIR =
  process.env.ANDROID_REMOTE_CONTROL_STATE_DIR ??
  join(homedir(), ".claude", "channels", "android-remote-control");

const ENV_FILE = join(STATE_DIR, ".env");

// Load .env file (env vars take precedence)
try {
  if (existsSync(ENV_FILE)) {
    chmodSync(ENV_FILE, 0o600);
    for (const [key, value] of parseEnvLines(readFileSync(ENV_FILE, "utf8"))) {
      if (process.env[key] === undefined) {
        process.env[key] = value;
      }
    }
  }
} catch {
  // Ignore read errors
}

const LISTEN_PORT = parseInt(process.env.LISTEN_PORT || "9090", 10);
const LISTEN_HOST = process.env.LISTEN_HOST || "127.0.0.1";
const AUTH_TOKEN = process.env.AUTH_TOKEN || "";
const PROMPT_TEMPLATE =
  process.env.PROMPT_TEMPLATE ||
  "This is an event from the Android Remote Control MCP server. Don't take actions unless the user has specified what to do.";

if (!AUTH_TOKEN) {
  process.stderr.write(
    "AUTH_TOKEN not set. Run /android-remote-control:configure <token> or set AUTH_TOKEN env var.\n"
  );
  process.exit(1);
}

// --- Constant-time token comparison ---
function validateToken(provided: string): boolean {
  const expected = Buffer.from(AUTH_TOKEN, "utf8");
  const actual = Buffer.from(provided, "utf8");
  if (expected.length !== actual.length) return false;
  return timingSafeEqual(expected, actual);
}

// --- MCP Server ---
const mcp = new Server(
  { name: "android-remote-control", version: "0.0.1" },
  {
    capabilities: {
      experimental: { "claude/channel": {} },
    },
    instructions: `Events from an Android device arrive as <channel> messages. ${PROMPT_TEMPLATE}`,
  }
);

await mcp.connect(new StdioServerTransport());

// --- HTTP Server ---
Bun.serve({
  port: LISTEN_PORT,
  hostname: LISTEN_HOST,
  async fetch(req: Request): Promise<Response> {
    const url = new URL(req.url);

    // Health check — no auth required
    if (req.method === "GET" && url.pathname === "/health") {
      return new Response(JSON.stringify({ status: "ok" }), {
        headers: { "Content-Type": "application/json" },
      });
    }

    if (req.method !== "POST") {
      return new Response("Method not allowed", { status: 405 });
    }

    if (url.pathname !== "/event") {
      return new Response("Not found", { status: 404 });
    }

    // Validate bearer token
    const auth = req.headers.get("authorization") || "";
    if (!auth.startsWith("Bearer ") || !validateToken(auth.slice(7))) {
      return new Response("Unauthorized", { status: 401 });
    }

    try {
      const event = (await req.json()) as {
        type: string;
        timestamp: string;
        data: unknown;
      };

      if (!event.type || !event.timestamp) {
        return new Response(
          JSON.stringify({ error: "Missing type or timestamp" }),
          { status: 400, headers: { "Content-Type": "application/json" } }
        );
      }

      // Format event content
      const content = [
        PROMPT_TEMPLATE,
        "",
        `Event type: ${event.type}`,
        `Timestamp: ${event.timestamp}`,
        "",
        JSON.stringify(event.data, null, 2),
      ].join("\n");

      // Push to Claude Code session — params structure matches official Telegram plugin
      mcp
        .notification({
          method: "notifications/claude/channel",
          params: {
            content,
            meta: {
              sender: event.type,
              ts: event.timestamp,
            },
          },
        })
        .catch((err) => {
          process.stderr.write(
            `android-remote-control channel: failed to deliver event to Claude: ${err}\n`
          );
        });

      return new Response(JSON.stringify({ status: "ok" }), {
        headers: { "Content-Type": "application/json" },
      });
    } catch {
      return new Response(JSON.stringify({ error: "Invalid request body" }), {
        status: 400,
        headers: { "Content-Type": "application/json" },
      });
    }
  },
});
