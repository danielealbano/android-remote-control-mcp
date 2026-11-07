import { expect, test } from "bun:test";
import { parseEnvLines } from "./env.ts";

test("reads a file with \\n line endings", () => {
  expect(parseEnvLines("AUTH_TOKEN=abc\nLISTEN_PORT=9191\n")).toEqual([
    ["AUTH_TOKEN", "abc"],
    ["LISTEN_PORT", "9191"],
  ]);
});

test("reads a file with \\r\\n line endings without keeping the \\r", () => {
  expect(parseEnvLines("AUTH_TOKEN=abc\r\nLISTEN_PORT=9191\r\n")).toEqual([
    ["AUTH_TOKEN", "abc"],
    ["LISTEN_PORT", "9191"],
  ]);
});

test("reads the last line without a line ending", () => {
  expect(parseEnvLines("AUTH_TOKEN=abc\r\nPROMPT_TEMPLATE=Hi there")).toEqual([
    ["AUTH_TOKEN", "abc"],
    ["PROMPT_TEMPLATE", "Hi there"],
  ]);
});

test("keeps the value after the first =, empty values and the file order", () => {
  expect(parseEnvLines("A=x=y\nB=\nA=second\n")).toEqual([
    ["A", "x=y"],
    ["B", ""],
    ["A", "second"],
  ]);
});

test("ignores blank lines and lines that are not KEY=value", () => {
  expect(parseEnvLines("\n# comment\r\n\r\nnot a pair\nKEY-1=v\nOK=1\n")).toEqual([["OK", "1"]]);
});
