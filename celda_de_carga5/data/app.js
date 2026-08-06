const el = {
  estado: document.getElementById('estado'),
  lcd0: document.getElementById('lcd0'),
  lcd1: document.getElementById('lcd1'),
  now: document.getElementById('now'),
  last: document.getElementById('last'),
  zona: document.getElementById('zona'),
  humedad: document.getElementById('humedad'),
  alarma: document.getElementById('alarma'),
  zoneBadge: document.getElementById('zoneBadge'),
  zoneLight: document.getElementById('zoneLight'),
  alarmBadge: document.getElementById('alarmBadge'),
  btnTare: document.getElementById('btnTare'),
  btnLeft: document.getElementById('btnLeft'),
  btnStop: document.getElementById('btnStop'),
  btnRight: document.getElementById('btnRight'),
  motorRange: document.getElementById('motorRange'),
  motorDir: document.getElementById('motorDir'),
  motorPos: document.getElementById('motorPos'),
  motorLimits: document.getElementById('motorLimits'),
  motorMarker: document.getElementById('motorMarker')
};

let requestedMotorDir = 0;
let rangeKeepAlive = null;

function setStatus(text, className) {
  el.estado.textContent = text;
  el.estado.className = `status ${className}`;
}

function fmt(value) {
  const n = Number(value);
  return Number.isFinite(n) ? n.toFixed(1) : '--';
}

function zoneClass(zona) {
  if (zona === 'rojo') return 'zone-red';
  if (zona === 'verde') return 'zone-green';
  if (zona === 'amarillo') return 'zone-yellow';
  return 'zone-blue';
}

function setZone(zona) {
  const name = zona || '--';
  const cls = zoneClass(zona);
  el.zona.textContent = name;
  el.zoneBadge.textContent = name.charAt(0).toUpperCase() + name.slice(1);
  el.zoneBadge.className = `badge ${cls}`;
  el.zoneLight.className = `zone-light ${cls}`;
}

function directionFromRange(value) {
  const n = Number(value);
  if (n > 25) return 1;
  if (n < -25) return -1;
  return 0;
}

function setMotorUi(dir) {
  if (dir > 0) el.motorDir.textContent = 'Derecha';
  else if (dir < 0) el.motorDir.textContent = 'Izquierda';
  else el.motorDir.textContent = 'Centro';
}

async function setMotor(dir, force = false) {
  dir = dir > 0 ? 1 : dir < 0 ? -1 : 0;
  if (!force && dir === requestedMotorDir) return;

  requestedMotorDir = dir;
  setMotorUi(dir);

  try {
    const response = await fetch(`/motor?dir=${dir}`, { method: 'POST' });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const data = await response.json();
    if (typeof data.dir === 'number') {
      requestedMotorDir = data.dir;
      setMotorUi(data.dir);
    }
  } catch (err) {
    setStatus('Error motor', 'alert');
    console.log(err);
  }
}

function stopMotor() {
  stopRangeKeepAlive();
  el.motorRange.value = 0;
  setMotor(0, true);
}

function stopRangeKeepAlive() {
  if (!rangeKeepAlive) return;
  clearInterval(rangeKeepAlive);
  rangeKeepAlive = null;
}

function updateRangeMotor() {
  const dir = directionFromRange(el.motorRange.value);
  setMotor(dir);

  if (dir === 0) {
    stopRangeKeepAlive();
    return;
  }

  if (!rangeKeepAlive) {
    rangeKeepAlive = setInterval(() => {
      const currentDir = directionFromRange(el.motorRange.value);
      if (currentDir === 0) {
        stopRangeKeepAlive();
        setMotor(0, true);
        return;
      }
      setMotor(currentDir, true);
    }, 300);
  }
}

function bindHold(button, dir) {
  let active = false;
  let repeat = null;

  button.addEventListener('pointerdown', (event) => {
    event.preventDefault();
    active = true;
    button.classList.add('active');
    if (button.setPointerCapture) button.setPointerCapture(event.pointerId);
    el.motorRange.value = dir * 100;
    setMotor(dir, true);
    repeat = setInterval(() => {
      if (active) setMotor(dir, true);
    }, 300);
  });

  const stop = () => {
    if (!active) return;
    active = false;
    if (repeat) {
      clearInterval(repeat);
      repeat = null;
    }
    button.classList.remove('active');
    stopMotor();
  };

  button.addEventListener('pointerup', stop);
  button.addEventListener('pointercancel', stop);
  button.addEventListener('lostpointercapture', stop);
}

el.btnTare.addEventListener('click', async () => {
  el.btnTare.disabled = true;
  el.btnTare.textContent = 'TARANDO';

  try {
    const response = await fetch('/tare', { method: 'POST' });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    setStatus('Tara enviada', 'ok');
  } catch (err) {
    setStatus('Error tara', 'alert');
    console.log(err);
  } finally {
    setTimeout(() => {
      el.btnTare.disabled = false;
      el.btnTare.textContent = 'TARA';
    }, 900);
  }
});

bindHold(el.btnLeft, -1);
bindHold(el.btnRight, 1);
el.btnStop.addEventListener('click', stopMotor);

el.motorRange.addEventListener('input', updateRangeMotor);

const events = new EventSource('/events');

events.onopen = () => setStatus('Conectado', 'ok');
events.onerror = () => setStatus('Reconectando', 'warn');

events.addEventListener('data', (event) => {
  try {
    const data = JSON.parse(event.data);

    if (data.peso_g !== undefined) el.now.textContent = fmt(data.peso_g);
    if (data.last_g !== undefined) el.last.textContent = fmt(data.last_g);
    if (data.lcd0 !== undefined) el.lcd0.textContent = data.lcd0;
    if (data.lcd1 !== undefined) el.lcd1.textContent = data.lcd1;

    setZone(data.zona);

    const humidity = Boolean(data.humedad);
    const alarm = Boolean(data.alarm);
    el.humedad.textContent = humidity ? 'SI' : 'NO';
    el.alarma.textContent = alarm ? 'ON' : 'OFF';
    el.alarmBadge.textContent = alarm ? 'Alarma' : 'Normal';
    el.alarmBadge.className = alarm ? 'badge alert' : 'badge ok';

    if (data.motor_pos !== undefined) el.motorPos.textContent = data.motor_pos;
    if (data.motor_left !== undefined && data.motor_right !== undefined) {
      el.motorLimits.textContent = `${data.motor_left} / ${data.motor_right}`;
    }

    el.btnLeft.disabled = data.motor_can_left === false;
    el.btnRight.disabled = data.motor_can_right === false;

    if (typeof data.motor_dir === 'number') {
      requestedMotorDir = data.motor_dir;
      setMotorUi(data.motor_dir);
      if (data.motor_dir === 0 && directionFromRange(el.motorRange.value) !== 0) {
        el.motorRange.value = 0;
      }
    }

    if (data.motor_pos !== undefined && data.motor_left !== undefined && data.motor_right !== undefined) {
      const left = Number(data.motor_left);
      const right = Number(data.motor_right);
      const pos = Number(data.motor_pos);
      const span = right - left;
      const pct = span > 0 ? Math.max(0, Math.min(100, ((pos - left) / span) * 100)) : 50;
      el.motorMarker.style.left = `${pct}%`;
    }
  } catch (err) {
    console.log(err);
  }
});
