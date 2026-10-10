const $ = (s, el = document) => el.querySelector(s);
const esc = s => String(s ?? '').replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

async function api(path, body, method) {
  const r = await fetch(path, body === undefined ? undefined
    : { method: method || 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  let data = null; try { data = await r.json(); } catch (e) { /* sin cuerpo */ }
  if (!r.ok) throw new Error((data && data.detail) || ('Error ' + r.status));
  return data;
}

function nav(active) {
  const links = [['/', 'Almacén', 'a'], ['/orden.html', 'Orden', 'o'], ['/piso.html', 'Piso', 'p']];
  $('#nav').innerHTML = links.map(l => `<a href="${l[0]}" class="${l[2] === active ? 'on' : ''}">${l[1]}</a>`).join('');
}

function table(cols, rows) {
  if (!rows.length) return '<p class="muted">Sin resultados</p>';
  return '<div class="scroll"><table><thead><tr>' + cols.map(c => `<th>${c[0]}</th>`).join('') + '</tr></thead><tbody>'
    + rows.map(r => '<tr>' + cols.map(c => `<td>${c[1](r)}</td>`).join('') + '</tr>').join('') + '</tbody></table></div>';
}

/** Abre la cámara y devuelve el texto del código (Code 128) o null si se cancela. */
function scanCode() {
  return new Promise(async resolve => {
    const ov = document.createElement('div');
    ov.className = 'overlay';
    ov.innerHTML = '<video playsinline muted></video><p class="muted" id="scanmsg" style="color:#fff">Apunta al código de barras</p><button class="btn">Cancelar</button>';
    document.body.appendChild(ov);
    const video = ov.querySelector('video'); let stream = null, reader = null, stop = false;
    const done = v => { stop = true; if (stream) stream.getTracks().forEach(t => t.stop()); if (reader) reader.reset(); ov.remove(); resolve(v); };
    ov.querySelector('button').onclick = () => done(null);
    const fail = m => { ov.querySelector('#scanmsg').textContent = m; };
    try {
      if ('BarcodeDetector' in window) {
        stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } });
        video.srcObject = stream; await video.play();
        let det; try { det = new BarcodeDetector({ formats: ['code_128'] }); } catch (e) { det = new BarcodeDetector(); }
        const loop = async () => {
          if (stop) return;
          try { const c = await det.detect(video); if (c.length) return done(c[0].rawValue); } catch (e) { /* reintenta */ }
          requestAnimationFrame(loop);
        };
        loop();
      } else { // respaldo para navegadores sin BarcodeDetector (necesita internet en el celular)
        await new Promise((ok, err) => {
          const s = document.createElement('script');
          s.src = 'https://cdn.jsdelivr.net/npm/@zxing/library@0.21.3/umd/index.min.js'; s.onload = ok; s.onerror = err;
          document.head.appendChild(s);
        });
        reader = new ZXing.BrowserMultiFormatReader();
        reader.decodeFromVideoDevice(null, video, (res) => { if (res && !stop) done(res.getText()); });
      }
    } catch (e) {
      fail('No se pudo abrir la cámara (necesita HTTPS o localhost). Cancela y escribe el código a mano.');
    }
  });
}
