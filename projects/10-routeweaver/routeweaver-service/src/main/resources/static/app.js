'use strict';

const $ = id => document.getElementById(id);
const terminal = new Set(['COMPLETED', 'CANCELLED', 'FAILED']);
const colors = ['#176b9b', '#a43454', '#487d28', '#7651a8', '#ab631d', '#1a827a'];
const reasonLabels = {
  STRANDED_CARGO: 'Stranded cargo',
  SKILLS_OR_VEHICLE_UNAVAILABLE: 'No available vehicle with the required skills',
  CAPACITY: 'No eligible vehicle has enough capacity',
  ONBOARD_UNSCHEDULED: 'Onboard cargo not scheduled; feasibility is not disproved',
  DEPOT_CARGO: 'No eligible vehicle at the depot',
  SEARCH_UNASSIGNED: 'Not assigned by this search; feasibility is not disproved'
};
let currentJob = null;
let currentPlan = null;
let currentProblem = null;
let currentValidation = null;
let activeProblem = null;
let baseline = null;
let pollTimer = null;
let generation = 0;
let busy = false;

async function api(path, body) {
  const headers = {};
  if ($('token').value) headers.Authorization = 'Bearer ' + $('token').value;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const response = await fetch(path, {
    method: body === undefined ? 'GET' : 'POST',
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    credentials: 'omit',
    cache: 'no-store'
  });
  const text = await response.text();
  let value;
  try { value = JSON.parse(text); }
  catch { throw new Error('The service returned an unreadable response (' + response.status + ').'); }
  if (!response.ok) {
    if (response.status === 401) throw new Error('Authentication required. Enter a valid Bearer token.');
    if (response.status === 403) throw new Error('Access denied. Check your tenant, scopes, and local browser origin.');
    if (response.status === 404) throw new Error('No matching resource was found for this tenant and identifier.');
    if (response.status === 409) throw new Error('The input or plan changed. Load the current plan before trying again.');
    if (response.status === 429) throw new Error('Solver capacity is full. Try again shortly.');
    throw new Error(value.message || value.code || 'Request failed (' + response.status + ').');
  }
  return value;
}

function stopPolling() {
  clearTimeout(pollTimer);
  pollTimer = null;
}

function controls() {
  $('load').disabled = busy;
  $('solve').disabled = busy || !activeProblem || (currentJob && !terminal.has(currentJob.state));
  $('cancel').disabled = busy || !currentJob || terminal.has(currentJob.state) || currentJob.state === 'CANCEL_REQUESTED';
  $('publish').disabled = busy || !currentJob || !['COMPLETED', 'CANCELLED'].includes(currentJob.state) || !currentJob.incumbent || !currentPlan;
}

function show(details, message, error = false) {
  $('state').textContent = JSON.stringify(details, null, 2);
  $('notice').textContent = message;
  $('notice').classList.toggle('error', error);
}

async function guarded(action) {
  if (busy) return;
  busy = true;
  controls();
  const expected = generation;
  try { await action(expected); }
  catch (error) {
    if (expected === generation) show({error: error.message}, error.message, true);
  } finally {
    busy = false;
    controls();
  }
}

function resetWorkspace() {
  generation++;
  stopPolling();
  currentJob = currentPlan = currentProblem = activeProblem = baseline = null;
  clearRenderedPlan();
  show({}, 'Connection details changed. Load the problem to continue.');
  controls();
}

function point(location) {
  return [40 + (location * 127 % 820), 30 + (location * 73 % 330)];
}

function svgElement(tag, attributes, text) {
  const element = document.createElementNS('http://www.w3.org/2000/svg', tag);
  Object.entries(attributes).forEach(([key, value]) => element.setAttribute(key, value));
  if (text !== undefined) element.textContent = text;
  return element;
}

function clearRenderedPlan() {
  currentValidation = null;
  $('timeline').replaceChildren();
  $('map').replaceChildren();
  $('route-count').textContent = 'No plan loaded';
  $('score').textContent = $('unassigned').textContent = 'No plan loaded.';
  $('unassigned-jobs').replaceChildren();
  $('score-metrics').replaceChildren();
  const all = document.createElement('option');
  all.value = '';
  all.textContent = 'All routes';
  $('vehicle-filter').replaceChildren(all);
  $('vehicle-filter').disabled = true;
  $('plan-summary').classList.remove('partial', 'invalid');
  $('plan-context').textContent = 'No validated plan';
  $('plan-status').textContent = 'Awaiting a result';
  $('unassigned-count').textContent = $('hard-count').textContent = '—';
  $('summary-explanation').textContent = 'A validated result is required before publication.';
  $('timeline-zone').textContent = "Times use the problem's service zone. Cargo remains on its observed vehicle.";
}

function timeFormatter(problem, validation) {
  const zone = problem.serviceZone === 'Z' ? 'UTC' : problem.serviceZone;
  const reference = problem.planningInstant || validation.timelines[0]?.stops[0]?.serviceStart
    || validation.timelines[0]?.returnedAt;
  try {
    const day = new Intl.DateTimeFormat('en-CA', {timeZone: zone, year: 'numeric', month: 'short', day: '2-digit'});
    const clock = new Intl.DateTimeFormat('en-CA', {timeZone: zone, hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23'});
    const referenceDay = day.format(new Date(reference));
    $('timeline-zone').textContent = referenceDay + ' · ' + zone + '. Times include the date when a stop crosses into another day.';
    return time => {
      const date = new Date(time);
      if (Number.isNaN(date.getTime())) return time + ' (outside browser calendar range)';
      const dateLabel = day.format(date);
      return (dateLabel === referenceDay ? '' : dateLabel + ' ') + clock.format(date);
    };
  } catch (unsupportedZoneOrDate) {
    $('timeline-zone').textContent = 'Service zone ' + zone + '; explicit timestamps shown because the browser cannot format this calendar or zone.';
    return time => time;
  }
}

function render(validation, problem, context = 'Published plan') {
  if (!validation) return;
  currentProblem = problem;
  currentValidation = validation;
  const unassigned = Object.keys(validation.candidate.unassigned).length;
  const metrics = {
    'Plan status': validation.violations.length ? 'Constraint violations' : unassigned ? 'Partially feasible' : 'Fully feasible',
    'Hard violations': validation.score.hard,
    'Unassigned jobs': unassigned,
    'Operating cost': validation.score.operatingCost.toLocaleString('en-CA'),
    'Disruption cost': validation.score.disruption.toLocaleString('en-CA'),
    'Input version': problem.version
  };
  $('plan-context').textContent = context + ' · input version ' + problem.version;
  $('plan-status').textContent = metrics['Plan status'];
  $('unassigned-count').textContent = unassigned;
  $('hard-count').textContent = validation.score.hard;
  $('plan-summary').classList.toggle('partial', unassigned > 0 && validation.score.hard === 0);
  $('plan-summary').classList.toggle('invalid', validation.score.hard > 0);
  $('summary-explanation').textContent = validation.score.hard > 0
    ? 'Constraint violations prevent dispatch. Inspect the validation details.'
    : unassigned > 0 ? unassigned + ' jobs remain unassigned. Review their reasons before publishing this partial plan.'
      : 'All jobs are assigned with no hard violations. Review the route details before publishing.';
  $('score-metrics').replaceChildren();
  Object.entries(metrics).forEach(([label, value]) => {
    const item = document.createElement('div');
    const term = document.createElement('dt');
    const detail = document.createElement('dd');
    term.textContent = label;
    detail.textContent = value;
    item.append(term, detail);
    $('score-metrics').append(item);
  });
  $('score').textContent = JSON.stringify({
    feasibility: validation.violations.length ? 'HARD_VIOLATIONS'
      : Object.keys(validation.candidate.unassigned).length ? 'PARTIAL_FEASIBLE' : 'FULLY_FEASIBLE',
    inputVersion: problem.version,
    serviceZone: problem.serviceZone,
    matrixVersion: problem.matrix.version,
    inputHash: validation.candidate.inputHash,
    objective: problem.objective,
    score: validation.score,
    baseline: baseline?.candidate.inputHash === validation.candidate.inputHash ? baseline.score : undefined
  }, null, 2);
  $('unassigned').textContent = Object.keys(validation.explanations).length
    ? JSON.stringify(validation.explanations, null, 2) : 'All demand is assigned.';
  $('unassigned-jobs').replaceChildren();
  const reasons = Object.entries(validation.explanations).sort(([left], [right]) => left.localeCompare(right));
  if (reasons.length === 0) {
    const row = document.createElement('tr');
    const cell = document.createElement('td');
    cell.colSpan = 2;
    cell.textContent = 'All demand is assigned.';
    row.append(cell);
    $('unassigned-jobs').append(row);
  }
  reasons.forEach(([jobId, reason]) => {
    const row = document.createElement('tr');
    const job = document.createElement('th');
    job.scope = 'row';
    job.textContent = jobId;
    const description = document.createElement('td');
    description.textContent = reasonLabels[String(reason).split(':', 1)[0]]
      || 'Unassigned; inspect the validation details';
    row.append(job, description);
    $('unassigned-jobs').append(row);
  });
  renderRoutes(validation, problem);
}

function renderRoutes(validation, problem) {
  const format = timeFormatter(problem, validation);
  const selected = $('vehicle-filter').value;
  const all = document.createElement('option');
  all.value = '';
  all.textContent = 'All routes';
  const options = validation.timelines.map(timeline => {
    const option = document.createElement('option');
    option.value = timeline.vehicleId;
    option.textContent = timeline.vehicleId + ' · ' + timeline.stops.length + ' stops';
    return option;
  });
  $('vehicle-filter').replaceChildren(all, ...options);
  $('vehicle-filter').disabled = validation.timelines.length === 0;
  $('vehicle-filter').value = validation.timelines.some(item => item.vehicleId === selected) ? selected : '';
  const filter = $('vehicle-filter').value;
  $('timeline').replaceChildren();
  $('map').replaceChildren(
    svgElement('title', {id: 'map-title'}, filter ? 'Stop sequence for ' + filter : 'Synthetic stop sequence'),
    svgElement('desc', {id: 'map-description'}, 'Read the matching shift timelines below for vehicle names, stop order and service times.')
  );
  $('route-count').textContent = filter ? filter + ' · 1 of ' + validation.timelines.length + ' vehicle routes'
    : validation.timelines.length + ' vehicle routes';
  validation.timelines.forEach((timeline, index) => {
    if (filter && timeline.vehicleId !== filter) return;
    const vehicle = problem.vehicles.find(item => item.id === timeline.vehicleId);
    const row = document.createElement('tr');
    const name = document.createElement('th');
    name.scope = 'row';
    name.textContent = timeline.vehicleId;
    const schedule = document.createElement('td');
    if (timeline.stops.length) {
      const sequence = document.createElement('ol');
      sequence.className = 'stop-list';
      timeline.stops.forEach(stop => {
        const item = document.createElement('li');
        const job = document.createElement('span');
        job.textContent = stop.jobId;
        const time = document.createElement('time');
        time.dateTime = stop.serviceStart;
        time.title = stop.serviceStart + ' · service zone ' + problem.serviceZone;
        time.textContent = format(stop.serviceStart);
        item.append(job, time);
        sequence.append(item);
      });
      schedule.append(sequence);
    } else schedule.textContent = vehicle.failed ? 'Broken; ' + vehicle.onboard.length + ' onboard jobs' : 'Standby / unscheduled';
    const returned = document.createElement('td');
    returned.textContent = format(timeline.returnedAt);
    row.append(name, schedule, returned);
    $('timeline').append(row);
    const locations = vehicle.failed ? [point(vehicle.currentLocation)]
      : [point(vehicle.currentLocation), ...timeline.stops.map(stop => point(stop.location)), point(problem.depot)];
    $('map').append(svgElement('polyline', {
      points: locations.map(value => value.join(',')).join(' '), stroke: colors[index % colors.length],
      fill: 'none', 'stroke-width': 2, 'aria-hidden': 'true'
    }));
    const current = point(vehicle.currentLocation);
    $('map').append(svgElement('text', {
      x: current[0], y: current[1] + 12 + index * 10, 'font-size': 11, fill: colors[index % colors.length]
    }, vehicle.id + (vehicle.failed ? ' BROKEN' : '')));
    timeline.stops.forEach(stop => {
      const location = point(stop.location);
      $('map').append(svgElement('text', {x: location[0], y: location[1], 'font-size': 10}, stop.jobId));
    });
  });
}

async function load(expected) {
  stopPolling();
  const id = encodeURIComponent($('problem').value.trim());
  if (!id) throw new Error('Enter a problem identifier.');
  const problem = await api('/v1/problems/' + id);
  const plan = await api('/v1/plans/' + id);
  const published = plan.solution ? await api('/v1/problems/' + id + '?version=' + plan.problemVersion) : problem;
  if (generation !== expected) return;
  activeProblem = problem;
  currentProblem = published;
  currentPlan = plan;
  currentJob = baseline = null;
  show({planRevision: plan.revision, activeInputVersion: problem.version, publishedInputVersion: plan.problemVersion},
    plan.solution ? 'Published plan loaded. Review routes or solve the active input.' : 'Problem loaded. Solve the active input to create a candidate.');
  if (plan.solution) render(plan.solution, published);
  else clearRenderedPlan();
}

async function poll(jobId, problem, expected) {
  try {
    const job = await api('/v1/solve-jobs/' + jobId);
    if (generation !== expected || currentJob?.id !== jobId) return;
    currentJob = job;
    if (job.incumbent && !baseline) baseline = await api('/v1/solve-jobs/' + jobId + '/baseline');
    if (generation !== expected || currentJob?.id !== jobId) return;
    show({job: job.id, state: job.state, inputVersion: job.problemVersion, stop: job.stopCondition},
      terminal.has(job.state) ? 'Solve ' + job.state.toLowerCase() + '. Review the candidate before publishing.'
        : job.state === 'CANCEL_REQUESTED' ? 'Cancellation requested. Waiting for the worker to stop.' : 'Solving input version ' + job.problemVersion + '…');
    render(job.incumbent, problem, 'Candidate plan');
    controls();
    if (!terminal.has(job.state)) pollTimer = setTimeout(() => poll(jobId, problem, expected), 500);
  } catch (error) {
    if (generation !== expected) return;
    show({error: error.message}, 'Progress updates stopped. ' + error.message + ' Load the plan to reconnect.', true);
    controls();
  }
}

$('vehicle-filter').addEventListener('change', () => {
  if (currentValidation && currentProblem) renderRoutes(currentValidation, currentProblem);
});

$('connection').addEventListener('submit', event => { event.preventDefault(); guarded(load); });
$('problem').addEventListener('input', resetWorkspace);
$('token').addEventListener('input', resetWorkspace);
$('solve').addEventListener('click', () => guarded(async expected => {
  const problem = activeProblem;
  baseline = null;
  const job = await api('/v1/problems/' + encodeURIComponent(problem.id) + '/solve', {
    expectedVersion: problem.version, requestId: crypto.randomUUID(), seed: 42, budgetMs: 5000, steps: 1000
  });
  if (generation !== expected) return;
  currentJob = job;
  show({job: job.id, state: job.state, inputVersion: job.problemVersion}, 'Solve submitted. The published plan is unchanged.');
  pollTimer = setTimeout(() => poll(job.id, problem, expected), 0);
}));
$('cancel').addEventListener('click', () => guarded(async expected => {
  const job = await api('/v1/solve-jobs/' + currentJob.id + '/cancel', {});
  if (generation !== expected) return;
  // A progress response may reach terminal state while this cancellation request is in flight.
  if (!terminal.has(currentJob.state)) currentJob = job;
  show({job: currentJob.id, state: currentJob.state}, terminal.has(currentJob.state)
    ? 'Solve ' + currentJob.state.toLowerCase() + '. Review the candidate before publishing.'
    : 'Cancellation requested. Waiting for the worker to stop.');
}));
$('publish').addEventListener('click', () => guarded(async expected => {
  const id = encodeURIComponent(activeProblem.id);
  const plan = await api('/v1/plans/' + id + '/publish', {
    solveJobId: currentJob.id, expectedVersion: currentJob.problemVersion, expectedRevision: currentPlan.revision
  });
  const problem = await api('/v1/problems/' + id + '?version=' + plan.problemVersion);
  if (generation !== expected) return;
  currentPlan = plan;
  currentProblem = problem;
  show({planRevision: plan.revision, publishedInputVersion: plan.problemVersion, status: 'Published independently validated plan'},
    'Plan revision ' + plan.revision + ' published for input version ' + plan.problemVersion + '.');
  render(plan.solution, problem);
}));
window.addEventListener('pagehide', stopPolling);
controls();
