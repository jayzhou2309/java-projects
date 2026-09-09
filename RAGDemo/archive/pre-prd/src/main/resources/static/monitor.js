let selectedId;
let latestRuns = [];
const byId = id => document.getElementById(id);

function render(runs) {
    if (!runs.some(run => run.id === selectedId)) selectedId = runs[0]?.id;
    byId('runs').replaceChildren();
    if (!runs.length) byId('runs').textContent = 'No requests recorded yet.';
    for (const run of runs) {
        const button = document.createElement('button');
        const state = run.events.filter(event => event.agent === 'Pipeline').at(-1)?.status ?? 'UNKNOWN';
        button.textContent = `${run.id.slice(0, 8)} · ${state}`;
        button.setAttribute('aria-pressed', String(run.id === selectedId));
        button.onclick = () => { selectedId = run.id; render(latestRuns); };
        byId('runs').append(button);
    }
    const run = runs.find(item => item.id === selectedId);
    byId('run-title').textContent = run ? `Run ${run.id}` : 'Select a run';
    byId('stages').replaceChildren();
    byId('events').replaceChildren();
    if (!run) return;
    const stages = new Map();
    for (const event of run.events) stages.set(event.agent, event);
    for (const [name, event] of stages) {
        const row = document.createElement('div');
        row.className = 'stage';
        row.textContent = name;
        const status = document.createElement('span');
        status.className = `status ${event.status}`;
        status.textContent = event.status;
        row.append(status);
        byId('stages').append(row);
    }
    for (const event of run.events) {
        const row = document.createElement('li');
        const time = document.createElement('time');
        time.dateTime = event.time;
        time.textContent = new Date(event.time).toLocaleTimeString();
        row.append(time, ` — ${event.agent}: ${event.message}`);
        byId('events').append(row);
    }
}

async function refresh() {
    try {
        const response = await fetch('/monitoring/runs', {cache: 'no-store', signal: AbortSignal.timeout(5000)});
        if (!response.ok) throw new Error('Monitor unavailable');
        latestRuns = await response.json();
        render(latestRuns);
        byId('connection').textContent = `Connected · updated ${new Date().toLocaleTimeString()}`;
    } catch {
        byId('connection').textContent = 'Disconnected · displayed data may be stale. Retrying…';
    } finally {
        setTimeout(refresh, 1000);
    }
}
refresh();
