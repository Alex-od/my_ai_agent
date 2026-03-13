const express = require('express');
const app = express();
app.use(express.json());

const PORT = 8082;

// ── Tool definitions ──────────────────────────────────────────────────────────

const TOOLS = [
  {
    name: 'calculate',
    description: 'Evaluate a safe mathematical expression and return the result.',
    inputSchema: {
      type: 'object',
      properties: {
        expression: { type: 'string', description: 'Math expression, e.g. "2 + 2 * 3"' },
      },
      required: ['expression'],
    },
  },
  {
    name: 'calculateDifference',
    description: 'Calculate the absolute difference between two numbers with formatted output.',
    inputSchema: {
      type: 'object',
      properties: {
        a: { type: 'number', description: 'First number' },
        b: { type: 'number', description: 'Second number' },
      },
      required: ['a', 'b'],
    },
  },
];

// ── Safe math evaluator ───────────────────────────────────────────────────────

function safeEval(expression) {
  // Allow only digits, operators, spaces, dots, parentheses
  if (!/^[\d\s+\-*/().,%^]+$/.test(expression)) {
    throw new Error(`Unsafe expression: ${expression}`);
  }
  // Replace ^ with ** for exponentiation
  const sanitized = expression.replace(/\^/g, '**');
  // eslint-disable-next-line no-new-func
  const result = Function(`"use strict"; return (${sanitized})`)();
  if (typeof result !== 'number' || !isFinite(result)) {
    throw new Error(`Invalid result for expression: ${expression}`);
  }
  return result;
}

// ── JSON-RPC dispatcher ───────────────────────────────────────────────────────

async function handleMethod(method, params) {
  switch (method) {

    case 'initialize':
      return {
        protocolVersion: '2024-11-05',
        capabilities: { tools: {} },
        serverInfo: { name: 'CalculatorMcpServer', version: '1.0' },
      };

    case 'tools/list':
      return { tools: TOOLS };

    case 'tools/call': {
      const { name, arguments: args } = params;

      if (name === 'calculate') {
        const result = safeEval(args.expression);
        return {
          content: [{
            type: 'text',
            text: JSON.stringify({ expression: args.expression, result }),
          }],
        };
      }

      if (name === 'calculateDifference') {
        const a = Number(args.a);
        const b = Number(args.b);
        const diff = Math.abs(a - b);
        const formatted = diff % 1 === 0 ? diff.toString() : diff.toFixed(2);
        return {
          content: [{
            type: 'text',
            text: JSON.stringify({
              a,
              b,
              difference: diff,
              formatted: `Difference: ${formatted}`,
            }),
          }],
        };
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
  console.log(`CalculatorMcpServer running on http://0.0.0.0:${PORT}`);
  console.log(`Tools: ${TOOLS.map(t => t.name).join(', ')}`);
});
