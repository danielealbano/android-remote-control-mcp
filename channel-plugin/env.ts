// Parses the plugin's .env file: one KEY=value per line, in file order.
// Lines may end with \n or \r\n (a file saved on Windows); other lines are ignored.
export function parseEnvLines(text: string): Array<[string, string]> {
  const entries: Array<[string, string]> = [];
  for (const line of text.split(/\r?\n/)) {
    const m = line.match(/^(\w+)=(.*)$/);
    if (m) {
      entries.push([m[1], m[2]]);
    }
  }
  return entries;
}
