import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { buildServer } from './server';
import { loadMcpState } from './state';

async function main(): Promise<void> {
  const server = buildServer(loadMcpState());
  const transport = new StdioServerTransport();
  await server.connect(transport);
  // Log to stderr — stdout is the MCP protocol channel.
  console.error('Stride MCP server running on stdio.');
}

main().catch((err) => {
  console.error('Stride MCP server failed to start:', err);
  process.exit(1);
});
