/* Base de datos de la app guardada en este dispositivo (IndexedDB), sin Claude ni servidor.
   La primera vez carga los datos de la planta (cifrados) usando la clave que viene en el link (#k=...). */
(function(){
  const DEMO = /[?&]ejemplo\b/.test(location.search);       // link de ejemplo: base aparte, no toca los datos reales
  window.PAPEL_DEMO = DEMO;
  const NOMBRE_DB = DEMO ? 'papel_metrocolor_ejemplo' : 'papel_metrocolor', TABLA = 'docs';
  const store = new Map();            // ruta -> datos
  const subs = new Set();
  let idb = null;

  function abrirIDB(){
    return new Promise((ok, mal)=>{
      const r = indexedDB.open(NOMBRE_DB, 1);
      r.onupgradeneeded = ()=>r.result.createObjectStore(TABLA);
      r.onsuccess = ()=>ok(r.result); r.onerror = ()=>mal(r.error);
    });
  }
  function tx(modo, fn){
    return new Promise((ok, mal)=>{
      const t = idb.transaction(TABLA, modo); const os = t.objectStore(TABLA);
      const res = fn(os); t.oncomplete = ()=>ok(res && res.result); t.onerror = ()=>mal(t.error);
    });
  }
  async function cargarTodo(){
    const claves = await tx('readonly', os=>os.getAllKeys());
    const valores = await tx('readonly', os=>os.getAll());
    claves.forEach((k,i)=>store.set(k, valores[i]));
  }
  const persistir = (ruta, datos)=>tx('readwrite', os=>datos===undefined ? os.delete(ruta) : os.put(datos, ruta));

  // ---------- datos iniciales cifrados (AES-GCM + PBKDF2) ----------
  const b64 = (s)=>Uint8Array.from(atob(s), c=>c.charCodeAt(0));
  async function descifrar(paquete, clave){
    const base = await crypto.subtle.importKey('raw', new TextEncoder().encode(clave), 'PBKDF2', false, ['deriveKey']);
    const k = await crypto.subtle.deriveKey({name:'PBKDF2', salt:b64(paquete.sal), iterations:paquete.iter, hash:'SHA-256'},
                                            base, {name:'AES-GCM', length:256}, false, ['decrypt']);
    const plano = await crypto.subtle.decrypt({name:'AES-GCM', iv:b64(paquete.iv)}, k, b64(paquete.datos));
    return JSON.parse(new TextDecoder().decode(plano));
  }
  function claveDelLink(){
    const m = location.hash.match(/k=([^&]+)/);
    if(m){
      const k = decodeURIComponent(m[1]);
      try{ localStorage.setItem('papel_clave', k); }catch(e){}
      history.replaceState(null, '', location.pathname + location.search);
      return k;
    }
    try{ return localStorage.getItem('papel_clave'); }catch(e){ return null; }
  }
  async function sembrar(){
    if(DEMO){ return sembrarEjemplo(); }
    if(store.get('_sistema/semilla')) return;
    const clave = claveDelLink();
    if(!clave) throw new Error('sin clave');
    const paquete = await (await fetch('datos.cifrado.json', {cache:'no-store'})).json();
    const docs = await descifrar(paquete, clave);
    for(const [ruta, datos] of Object.entries(docs)){
      if(!store.has(ruta)){ store.set(ruta, datos); await persistir(ruta, datos); }
    }
    const marca = {cargada:new Date().toISOString(), version:paquete.version};
    store.set('_sistema/semilla', marca); await persistir('_sistema/semilla', marca);
  }

  // ---------- datos del link de ejemplo ----------
  async function sembrarEjemplo(){
    try{ sessionStorage.setItem('auditApp_session_ejemplo', JSON.stringify({user:'ejemplo'})); }catch(e){}
    if(store.get('_sistema/ejemplo_v1')) return;
    const docs = {
      'cuentas/ejemplo': {usuario:'ejemplo', nombre:'Ejemplo (Armando Medina)', rol:'administrador', estado:'activo'},
      'auditorias/ej01': {fecha:'2026-10-20', tipo:'Clasificación', rotativa:'M601', auditor:'Adrián Sánchez Ramón', hallazgo:'', origen:'Ronda semanal', createdAt:'2026-10-20T15:00:00Z'},
      'auditorias/ej02': {fecha:'2026-10-21', tipo:'Arranque', rotativa:'M601', auditor:'Adrián Sánchez Ramón', origen:'Orden 45210 · turno 2',
        hallazgo:'Arranque con 450 giros; el estándar es 250. Registro fuera en la unidad 3 (3.ª vez en el mes).',
        contencion:'Sí', detContencion:'Se paró, se ajustó el registro y se separaron 200 giros de papel malo.',
        escalamiento:'Sí', detEscalamiento:'Supervisor turno 2 · 21/10 15:40', rcaFolio:'RCA-2026-001', createdAt:'2026-10-21T21:40:00Z'},
      'auditorias/ej03': {fecha:'2026-10-28', tipo:'Sobretiro', rotativa:'Sunday 1', auditor:'Adrián Sánchez Ramón', origen:'Orden 45390',
        hallazgo:'Sobretiro de 3.5 % contra 2.5 % autorizado en la orden.', contencion:'Sí', detContencion:'Se ajustó el contador de salida al tiraje autorizado.',
        escalamiento:'No', detEscalamiento:'', createdAt:'2026-10-28T17:00:00Z'},
      'auditorias/ej04': {fecha:'2026-11-02', tipo:'Merma', rotativa:'M602', auditor:'Adrián Sánchez Ramón', origen:'Pareto de desperdicio — Doblador M602',
        hallazgo:'Doblador de la M602 con atascos repetidos: 380 giros de merma en el turno. La cuchilla de corte muestra desgaste.',
        contencion:'Sí', detContencion:'Se bajó la velocidad a 28,000 y se limpió el doblador; se separó el producto con corte irregular.',
        escalamiento:'Sí', detEscalamiento:'Mantenimiento turno 1 · 02/11 10:15', rcaFolio:'RCA-2026-002', createdAt:'2026-11-02T16:15:00Z'},
      'auditorias/ej05': {fecha:'2026-11-02', tipo:'Handoffs', rotativa:'M602', auditor:'Adrián Sánchez Ramón', origen:'Cambio de turno 1 → 2',
        hallazgo:'La bitácora de entrega no indica la bobina en uso ni el ajuste pendiente de tinta.', contencion:'', detContencion:'',
        escalamiento:'', detEscalamiento:'', createdAt:'2026-11-02T20:00:00Z'},
      'rca/RCA-2026-001': {folio:'RCA-2026-001', fecha:'2026-10-21', maquina:'M601', origen:'Arranque', categoria:'Método',
        problema:'Arranque con 450 giros; el estándar es 250. Registro fuera en la unidad 3 (3.ª vez en el mes).',
        causa:'¿Por qué 450 giros? El registro tarda en quedar. ¿Por qué? Cada operador preajusta distinto. ¿Por qué? No existe un estándar de preajuste de registro.',
        accion:'Crear hoja de preajuste de registro por unidad y capacitar a los 3 turnos.',
        responsable:'Ana Patricia Acosta · Jefa de Producción', ejecutor:'Supervisor turno 2', compromiso:'2026-10-31', cierre:'2026-10-30',
        evidencia:'4 arranques seguidos con menos de 250 giros (prom. 230) y hoja de preajuste firmada por los 3 turnos.', auditoriaId:'ej02', createdAt:'2026-10-21T22:00:00Z'},
      'rca/RCA-2026-002': {folio:'RCA-2026-002', fecha:'2026-11-02', maquina:'M602', origen:'Merma', categoria:'Máquina',
        problema:'Doblador de la M602 con atascos repetidos: 380 giros de merma en el turno. La cuchilla de corte muestra desgaste.',
        causa:'¿Por qué hay atascos? El corte sale irregular. ¿Por qué? La cuchilla está desgastada. ¿Por qué? No tiene frecuencia de cambio en el plan de mantenimiento preventivo; se cambia hasta que falla.',
        accion:'Cambiar la cuchilla y agregar su cambio al preventivo cada 1,500,000 giros, con registro de contador.',
        responsable:'Mario Bernal · Jefe de Mantenimiento', ejecutor:'Técnico mecánico turno 1', compromiso:'2026-11-09', cierre:'', evidencia:'',
        auditoriaId:'ej04', createdAt:'2026-11-02T16:30:00Z'},
    };
    for(const [ruta, datos] of Object.entries(docs)){ store.set(ruta, datos); await persistir(ruta, datos); }
    const marca = {cargada:new Date().toISOString()};
    store.set('_sistema/ejemplo_v1', marca); await persistir('_sistema/ejemplo_v1', marca);
  }

  // ---------- interfaz tipo base de datos que usa la app ----------
  const copia = (x)=>x==null ? x : JSON.parse(JSON.stringify(x));
  const hijos = (col)=>[...store.keys()].filter(k=>k.startsWith(col+'/') && k.split('/').length===col.split('/').length+1).sort();
  const snapDoc = (ruta)=>({id:ruta.split('/').pop(), exists:store.has(ruta), data:()=>copia(store.get(ruta))});
  const snapCol = (col)=>{ const d = hijos(col).map(k=>({id:k.split('/').pop(), exists:true, data:()=>copia(store.get(k))}));
                           return {docs:d, size:d.length, empty:!d.length, forEach:(f)=>d.forEach(f)}; };
  function avisar(ruta){
    subs.forEach(s=>{
      const toca = s.tipo==='doc' ? s.ruta===ruta : ruta.startsWith(s.ruta+'/') && ruta.split('/').length===s.ruta.split('/').length+1;
      if(toca) setTimeout(()=>{ try{ s.cb(s.tipo==='doc' ? snapDoc(s.ruta) : snapCol(s.ruta)); }catch(e){ console.error(e); } }, 0);
    });
  }
  async function escribir(ruta, datos){
    if(datos===undefined) store.delete(ruta); else store.set(ruta, copia(datos));
    await persistir(ruta, datos===undefined ? undefined : copia(datos));
    avisar(ruta); marcarCambio();
  }
  const nuevoId = ()=>Date.now().toString(36)+Math.random().toString(36).slice(2,8);
  function docRef(ruta){
    return { id:ruta.split('/').pop(), path:ruta,
      get: async()=>snapDoc(ruta),
      set: (d)=>escribir(ruta, d),
      update: (d)=>escribir(ruta, {...(store.get(ruta)||{}), ...copia(d)}),
      delete: ()=>escribir(ruta, undefined),
      onSnapshot: (cb, err)=>{ const s={tipo:'doc', ruta, cb}; subs.add(s); setTimeout(()=>cb(snapDoc(ruta)),0); return ()=>subs.delete(s); },
      collection: (c)=>colRef(ruta+'/'+c) };
  }
  function colRef(ruta){
    const c = { path:ruta,
      doc: (id)=>docRef(ruta+'/'+(id||nuevoId())),
      add: async(d)=>{ const r = docRef(ruta+'/'+nuevoId()); await r.set(d); return r; },
      get: async()=>snapCol(ruta),
      onSnapshot: (cb, err)=>{ const s={tipo:'col', ruta, cb}; subs.add(s); setTimeout(()=>cb(snapCol(ruta)),0); return ()=>subs.delete(s); },
      where: ()=>c, orderBy: ()=>c, limit: ()=>c };
    return c;
  }
  const db = Object.freeze({collection:colRef, doc:docRef});

  const listo = (async()=>{
    try{
      idb = await abrirIDB(); await cargarTodo(); await sembrar();
      try{ await navigator.storage?.persist?.(); }catch(e){}
      return db;
    }catch(e){
      console.error(e);
      mostrarAviso(e.message==='sin clave'
        ? 'Abre la app con el link completo que te compartieron (incluye la clave de acceso).'
        : 'No se pudieron cargar los datos de este dispositivo: '+e.message);
      return idb ? db : null;
    }
  })();

  function idEquipo(){
    try{ let id = localStorage.getItem('papel_equipo'); if(!id){ id='eq_'+nuevoId(); localStorage.setItem('papel_equipo', id); } return id; }
    catch(e){ return 'eq_temporal'; }
  }
  const user = Object.freeze({ id: async()=>idEquipo(), isOwner: async()=>false, canEdit: async()=>true });
  window.claude = { use: async(n)=> n==='db' ? listo : n==='user' ? user : null };

  // ---------- respaldo: descargar / restaurar todo en un archivo ----------
  function marcarCambio(){ try{ localStorage.setItem('papel_cambios', String(Date.now())); }catch(e){} }
  function descargarRespaldo(){
    const todo = {}; store.forEach((v,k)=>{ if(!k.startsWith('_sistema/')) todo[k]=v; });
    const blob = new Blob([JSON.stringify({app:'papel_metrocolor', fecha:new Date().toISOString(), docs:todo})], {type:'application/json'});
    const a = document.createElement('a'); a.href = URL.createObjectURL(blob);
    a.download = 'respaldo_papel_'+new Date().toISOString().slice(0,10)+'.json'; document.body.appendChild(a); a.click();
    setTimeout(()=>{ URL.revokeObjectURL(a.href); a.remove(); }, 3000);
  }
  async function restaurar(archivo){
    try{
      const j = JSON.parse(await archivo.text());
      if(j.app!=='papel_metrocolor' || !j.docs) throw new Error('no es un respaldo de esta app');
      const n = Object.keys(j.docs).length;
      if(!confirm('Se cargarán '+n+' registros del respaldo (se combinan con los de este dispositivo). ¿Continuar?')) return;
      for(const [k,v] of Object.entries(j.docs)) await escribir(k, v);
      alert('Respaldo cargado: '+n+' registros.');
    }catch(e){ alert('No se pudo cargar el respaldo: '+e.message); }
  }
  function mostrarAviso(t){
    const f = ()=>{ const d=document.createElement('div'); d.textContent=t;
      d.style.cssText='position:fixed;top:0;left:0;right:0;z-index:9999;background:#b3261e;color:#fff;padding:12px 16px;font:15px system-ui;text-align:center';
      document.body.appendChild(d); };
    document.body ? f() : document.addEventListener('DOMContentLoaded', f);
  }
  document.addEventListener('DOMContentLoaded', ()=>{
    const caja = document.createElement('div');
    caja.style.cssText='position:fixed;right:12px;bottom:12px;z-index:9000;display:flex;gap:6px';
    const b = (txt, fn)=>{ const x=document.createElement('button'); x.type='button'; x.textContent=txt; x.onclick=fn;
      x.style.cssText='border:0;border-radius:20px;padding:8px 12px;font:600 13px system-ui;background:#0b5cad;color:#fff;box-shadow:0 2px 8px #0003;cursor:pointer'; return x; };
    const inp = document.createElement('input'); inp.type='file'; inp.accept='.json,application/json'; inp.style.display='none';
    inp.onchange = ()=>{ if(inp.files[0]) restaurar(inp.files[0]); inp.value=''; };
    caja.append(b('⬇ Respaldo', descargarRespaldo), b('⬆ Cargar respaldo', ()=>inp.click()), inp);
    document.body.appendChild(caja);
    const st = document.getElementById('dbStatus');
    if(st) new MutationObserver(()=>{ if(st.textContent.includes('Conectado') && !st.textContent.includes('dispositivo')) st.textContent='● Guardado en este dispositivo'; }).observe(st, {childList:true, characterData:true, subtree:true});
  });
})();
