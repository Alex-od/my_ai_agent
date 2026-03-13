const express = require('express');
const fs = require('fs');
const path = require('path');
const app = express();
app.use(express.json());

const PORT = 8081;
const FILES_DIR = path.join(__dirname, 'files');

if (!fs.existsSync(FILES_DIR)) {
  fs.mkdirSync(FILES_DIR, { recursive: true });
}

// ── Tool definitions ──────────────────────────────────────────────────────────

const TOOLS = [
  {
    name: 'saveFile',
    description: 'Save text content to a file in the files/ directory.',
    inputSchema: {
      type: 'object',
      properties: {
        path: { type: 'string', description: 'Filename, e.g. "report.txt"' },
        content: { type: 'string', description: 'Text content to save' },
      },
      required: ['path', 'content'],
    },
  },
  {
    name: 'readFile',
    description: 'Read text content from a file in the files/ directory.',
    inputSchema: {
      type: 'object',
      properties: {
        path: { type: 'string', description: 'Filename, e.g. "report.txt"' },
      },
      required: ['path'],
    },
  },
];

// ── JSON-RPC dispatcher ───────────────────────────────────────────────────────

async function handleMethod(method, params) {
  switch (method) {

    case 'initialize':
      return {
        protocolVersion: '2024-11-05',
        capabilities: { tools: {} },
        serverInfo: { name: 'FileMcpServer', version: '1.0' },
      };

    case 'tools/list':
      return { tools: TOOLS };

    case 'tools/call': {
      const { name, arguments: args } = params;

      if (name === 'saveFile') {
        const filename = path.basename(args.path); // prevent path traversal
        const filePath = path.join(FILES_DIR, filename);
        fs.writeFileSync(filePath, args.content, 'utf8');
        return { content: [{ type: 'text', text: `Saved successfully: files/${filename}` }] };
      }

      if (name === 'readFile') {
        const filename = path.basename(args.path);
        const filePath = path.join(FILES_DIR, filename);
        if (!fs.existsSync(filePath)) {
          throw new Error(`File not found: ${filename}`);
        }
        const content = fs.readFileSync(filePath, 'utf8');
        return { content: [{ type: 'text', text: content }] };
      }

      throw new Error(`Unknown tool: ${name}`);
    }

    default:
      throw new Error(`Unknown method: ${method}`);
  }
}

// ── HTTP endpoint ─────────────────────────────────────────────────────────────

app.post('/mcp', async (req, res) => {
  const { id, method, params = {} } = req.body;
  console.log(`→ [${method}]`, params.name ?? '');
  try {
    const result = await handleMethod(method, params);
    console.log(`← [${method}] OK`);
    res.json({ jsonrpc: '2.0', id, result });
  } catch (e) {
    console.error(`← [${method}] ERROR:`, e.message);
    res.json({ jsonrpc: '2.0', id, error: { code: -32000, message: e.message } });
  }
});

app.listen(PORT, '0.0.0.0', () => {
  console.log(`FileMcpServer running on http://0.0.0.0:${PORT}`);
  console.log(`Tools: ${TOOLS.map(t => t.name).join(', ')}`);
  console.log(`Files directory: ${FILES_DIR}`);
});
