const express = require('express');
const cron = require('node-cron');
const fs = require('fs');
const path = require('path');
const app = express();
app.use(express.json());

const PORT = 8080;

// ── Persistence ───────────────────────────────────────────────────────────────

const TASKS_FILE = path.join(__dirname, 'tasks.json');
const RESULTS_FILE = path.join(__dirname, 'results.json');

function loadTasks() {
  try { return JSON.parse(fs.readFileSync(TASKS_FILE, 'utf8')); } catch { return {}; }
}
function saveTasks() {
  fs.writeFileSync(TASKS_FILE, JSON.stringify(tasks, null, 2));
}
function loadResults() {
  try { return JSON.parse(fs.readFileSync(RESULTS_FILE, 'utf8')); } catch { return {}; }
}
function saveResults() {
  fs.writeFileSync(RESULTS_FILE, JSON.stringify(results, null, 2));
}

let tasks = loadTasks();
let results = loadResults();
const cronJobs = new Map();

// ── Tool definitions ──────────────────────────────────────────────────────────

const TOOLS = [
  {
    name: 'get_current_weather',
    description: 'Get current weather for a city: temperature, humidity, wind speed.',
    inputSchema: {
      type: 'object',
      properties: {
        city: { type: 'string', description: 'City name, e.g. "Kyiv"' },
      },
      required: ['city'],
    },
  },
  {
    name: 'get_forecast',
    description: 'Get weather forecast for a city for the next N days.',
    inputSchema: {
      type: 'object',
      properties: {
        city: { type: 'string', description: 'City name, e.g. "London"' },
        days: { type: 'integer', description: 'Number of days (1–7)', default: 3 },
      },
      required: ['city'],
    },
  },
  {
    name: 'schedule_task',
    description: 'Create a scheduled task that runs a tool periodically.',
    inputSchema: {
      type: 'object',
      properties: {
        taskId: { type: 'string', description: 'Unique task identifier' },
        description: { type: 'string', description: 'Human-readable description' },
        cronExpression: { type: 'string', description: 'Cron expression, e.g. "*/5 * * * *"' },
        toolName: { type: 'string', enum: ['get_current_weather', 'get_forecast'], description: 'Tool to call' },
        toolArgs: { type: 'object', description: 'Arguments for the tool, e.g. {"city":"Kyiv"}' },
      },
      required: ['taskId', 'cronExpression', 'toolName', 'toolArgs'],
    },
  },
  {
    name: 'stop_task',
    description: 'Stop a running scheduled task.',
    inputSchema: {
      type: 'object',
      properties: {
        taskId: { type: 'string', description: 'Task ID to stop' },
      },
      required: ['taskId'],
    },
  },
  {
    name: 'delete_task',
    description: 'Delete a scheduled task and its results.',
    inputSchema: {
      type: 'object',
      properties: {
        taskId: { type: 'string', description: 'Task ID to delete' },
      },
      required: ['taskId'],
    },
  },
  {
    name: 'list_tasks',
    description: 'List all scheduled tasks.',
    inputSchema: { type: 'object', properties: {} },
  },
  {
    name: 'get_task_results',
    description: 'Get recent results for a scheduled task.',
    inputSchema: {
      type: 'object',
      properties: {
        taskId: { type: 'string', description: 'Task ID' },
        limit: { type: 'integer', description: 'Max results to return (default 10, max 50)', default: 10 },
      },
      required: ['taskId'],
    },
  },
];

// ── Open-Meteo helpers ────────────────────────────────────────────────────────

async function geocode(city) {
  const url = `https://geocoding-api.open-meteo.com/v1/search?name=${encodeURIComponent(city)}&count=1&language=en&format=json`;
  const res = await fetch(url);
  const data = await res.json();
  const loc = data.results?.[0];
  if (!loc) throw new Error(`City not found: ${city}`);
  return { lat: loc.latitude, lon: loc.longitude, name: loc.name, country: loc.country };
}

async function getCurrentWeather(city) {
  const { lat, lon, name, country } = await geocode(city);
  const url = `https://api.open-meteo.com/v1/forecast?latitude=${lat}&longitude=${lon}` +
    `&current=temperature_2m,relative_humidity_2m,wind_speed_10m,weather_code` +
    `&wind_speed_unit=ms`;
  const res = await fetch(url);
  const data = await res.json();
  const c = data.current;
  return {
    city: `${name}, ${country}`,
    temperature: `${c.temperature_2m}°C`,
    humidity: `${c.relative_humidity_2m}%`,
    wind_speed: `${c.wind_speed_10m} m/s`,
    condition: weatherCodeToText(c.weather_code),
  };
}

async function getForecast(city, days = 3) {
  days = Math.min(Math.max(parseInt(days) || 3, 1), 7);
  const { lat, lon, name, country } = await geocode(city);
  const url = `https://api.open-meteo.com/v1/forecast?latitude=${lat}&longitude=${lon}` +
    `&daily=temperature_2m_max,temperature_2m_min,precipitation_sum,weather_code` +
    `&forecast_days=${days}&timezone=auto`;
  const res = await fetch(url);
  const data = await res.json();
  const d = data.daily;
  const forecast = d.time.map((date, i) => ({
    date,
    max: `${d.temperature_2m_max[i]}°C`,
    min: `${d.temperature_2m_min[i]}°C`,
    precipitation: `${d.precipitation_sum[i]} mm`,
    condition: weatherCodeToText(d.weather_code[i]),
  }));
  return { city: `${name}, ${country}`, forecast };
}

function weatherCodeToText(code) {
  if (code === 0) return 'Clear sky';
  if (code <= 3) return 'Partly cloudy';
  if (code <= 49) return 'Foggy';
  if (code <= 59) return 'Drizzle';
  if (code <= 69) return 'Rain';
  if (code <= 79) return 'Snow';
  if (code <= 82) return 'Rain showers';
  if (code <= 86) return 'Snow showers';
  return 'Thunderstorm';
}

// ── Scheduler helpers ─────────────────────────────────────────────────────────

async function runTask(taskId) {
  const task = tasks[taskId];
  if (!task) return;
  let success = true, data;
  try {
    if (task.toolName === 'get_current_weather') {
      data = await getCurrentWeather(task.toolArgs.city);
    } else if (task.toolName === 'get_forecast') {
      data = await getForecast(task.toolArgs.city, task.toolArgs.days);
    } else {
      data = `Unknown tool: ${task.toolName}`;
    }
  } catch (e) {
    success = false;
    data = e.message;
  }
  results[taskId] = [
    { runAt: Date.now(), success, data },
    ...(results[taskId] || []),
  ].slice(0, 100);
  task.lastRunAt = Date.now();
  task.runCount = (task.runCount || 0) + 1;
  saveTasks();
  saveResults();
  console.log(`[cron] ${taskId} ran — success:${success}`);
}

function registerCronJob(taskId) {
  const task = tasks[taskId];
  const job = cron.schedule(task.cronExpression, () => runTask(taskId));
  cronJobs.set(taskId, job);
}

// Restore running tasks on startup
Object.keys(tasks).forEach(taskId => {
  if (tasks[taskId].status === 'running') {
    try {
      registerCronJob(taskId);
      console.log(`[init] restored cron for ${taskId} (${tasks[taskId].cronExpression})`);
    } catch (e) {
      console.error(`[init] failed to restore ${taskId}: ${e.message}`);
    }
  }
});

// ── JSON-RPC dispatcher ───────────────────────────────────────────────────────

async function handleMethod(method, params) {
  switch (method) {

    case 'initialize':
      return {
        protocolVersion: '2024-11-05',
        capabilities: { tools: {} },
        serverInfo: { name: 'WeatherMcpServer', version: '1.0' },
      };

    case 'tools/list':
      return { tools: TOOLS };

    case 'tools/call': {
      const { name, arguments: args } = params;

      if (name === 'get_current_weather') {
        const result = await getCurrentWeather(args.city);
        return { content: [{ type: 'text', text: JSON.stringify(result, null, 2) }] };
      }

      if (name === 'get_forecast') {
        const result = await getForecast(args.city, args.days);
        return { content: [{ type: 'text', text: JSON.stringify(result, null, 2) }] };
      }

      if (name === 'schedule_task') {
        const { taskId, description = '', cronExpression, toolName, toolArgs } = args;
        if (!cron.validate(cronExpression)) {
          throw new Error(`Invalid cron expression: ${cronExpression}`);
        }
        if (tasks[taskId]) {
          throw new Error(`Task already exists: ${taskId}`);
        }
        tasks[taskId] = {
          taskId,
          description,
          cronExpression,
          toolName,
          toolArgs,
          status: 'running',
          createdAt: Date.now(),
          lastRunAt: null,
          runCount: 0,
        };
        saveTasks();
        registerCronJob(taskId);
        console.log(`[schedule] created ${taskId} (${cronExpression})`);
        return { content: [{ type: 'text', text: JSON.stringify({ taskId, cronExpression, status: 'running' }) }] };
      }

      if (name === 'stop_task') {
        const { taskId } = args;
        if (!tasks[taskId]) throw new Error(`Task not found: ${taskId}`);
        const job = cronJobs.get(taskId);
        if (job) { job.stop(); cronJobs.delete(taskId); }
        tasks[taskId].status = 'stopped';
        saveTasks();
        console.log(`[schedule] stopped ${taskId}`);
        return { content: [{ type: 'text', text: JSON.stringify({ taskId, status: 'stopped' }) }] };
      }

      if (name === 'delete_task') {
        const { taskId } = args;
        if (!tasks[taskId]) throw new Error(`Task not found: ${taskId}`);
        const job = cronJobs.get(taskId);
        if (job) { job.stop(); cronJobs.delete(taskId); }
        delete tasks[taskId];
        delete results[taskId];
        saveTasks(); saveResults();
        console.log(`[schedule] deleted ${taskId}`);
        return { content: [{ type: 'text', text: JSON.stringify({ taskId, status: 'deleted' }) }] };
      }

      if (name === 'list_tasks') {
        const list = Object.values(tasks).map(t => ({
          taskId: t.taskId,
          description: t.description,
          cronExpression: t.cronExpression,
          toolName: t.toolName,
          status: t.status,
          lastRunAt: t.lastRunAt,
          runCount: t.runCount,
        }));
        return { content: [{ type: 'text', text: JSON.stringify(list, null, 2) }] };
      }

      if (name === 'get_task_results') {
        const { taskId, limit = 10 } = args;
        if (!tasks[taskId]) throw new Error(`Task not found: ${taskId}`);
        const cap = Math.min(parseInt(limit) || 10, 50);
        const taskResults = (results[taskId] || []).slice(0, cap);
        return { content: [{ type: 'text', text: JSON.stringify({ taskId, results: taskResults }, null, 2) }] };
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
  console.log(`WeatherMcpServer running on http://0.0.0.0:${PORT}`);
  console.log(`Tools: ${TOOLS.map(t => t.name).join(', ')}`);
});
