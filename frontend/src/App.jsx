import React, { useState, useEffect, useRef } from 'react';
import './App.css';

const API_URL = "http://localhost:8080/api/v1";

export default function App() {
  const [libros, setLibros] = useState([]);
  const [alumnos, setAlumnos] = useState([]);
  const [prestamos, setPrestamos] = useState([]);
  const [salud, setSalud] = useState({ estado: "CARGANDO", disyuntor: "CERRADO", servicios: {} });
  const [colaPendiente, setColaPendiente] = useState([]);
  const [logs, setLogs] = useState([]);

  const [idAlumno, setIdAlumno] = useState('');
  const [idLibro, setIdLibro] = useState('');
  const [nuevoIdAlumno, setNuevoIdAlumno] = useState('');
  const [nuevoNombreAlumno, setNuevoNombreAlumno] = useState('');
  const [nuevaUniversidad, setNuevaUniversidad] = useState('');
  const [nuevoIdLibro, setNuevoIdLibro] = useState('');
  const [nuevoTituloLibro, setNuevoTituloLibro] = useState('');

  const colaRef = useRef(colaPendiente);
  colaRef.current = colaPendiente;

  const registrarLog = (msg, tipo = "INFO") => {
    const t = new Date().toLocaleTimeString();
    setLogs(prev => [`[${t}] [${tipo}] ${msg}`, ...prev]);
  };

  const consultarSaludYDatos = async () => {
    try {
      const resS = await fetch(`${API_URL}/salud`);
      const dataS = await resS.json();
      setSalud(dataS);

      // Si Préstamos volvió en línea y hay pedidos en cola, vaciar cola con Idempotency-Key
      if (dataS.servicios?.prestamos && colaRef.current.length > 0) {
        const pendientes = [...colaRef.current];
        setColaPendiente([]);
        for (const item of pendientes) {
          registrarLog(`Reenviando préstamo en cola (Key: ${item.idemKey.slice(0, 8)}...)`, "SISTEMA");
          await ejecutarPrestamo(item.idAlumno, item.idLibro, item.idemKey);
        }
      }

      const [resL, resA, resP] = await Promise.all([
        fetch(`${API_URL}/libros`),
        fetch(`${API_URL}/alumnos`),
        fetch(`${API_URL}/prestamos`)
      ]);

      if (resL.ok) setLibros(await resL.json());
      if (resA.ok) setAlumnos(await resA.json());
      if (resP.ok) setPrestamos(await resP.json());
    } catch {
      setSalud({ estado: "CAÍDO", disyuntor: "ABIERTO", servicios: {} });
    }
  };

  useEffect(() => {
    consultarSaludYDatos();
    const timer = setInterval(consultarSaludYDatos, 2000);
    return () => clearInterval(timer);
  }, []);

  const ejecutarPrestamo = async (alId, libId, idemKey) => {
    try {
      const res = await fetch(`${API_URL}/prestamos`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Idempotency-Key": idemKey
        },
        body: JSON.stringify({ idAlumno: alId, idLibro: libId })
      });

      const data = await res.json();

      if (res.status === 503) {
        registrarLog(`503 Service Unavailable: ${data.error}. Préstamo guardado en cola.`, "ERROR");
        setColaPendiente(prev => [...prev, { idAlumno: alId, idLibro: libId, idemKey }]);
        return;
      }

      if (!res.ok) {
        registrarLog(`Error (${res.status}): ${data.error}`, "ERROR");
      } else {
        const repetido = res.headers.get("Idempotent-Replayed") ? " [Idempotente]" : "";
        registrarLog(`Préstamo ${data.folio} completado: ${data.alumno} -> ${data.libro}${repetido}`, "EXITO");
        consultarSaludYDatos();
      }
    } catch {
      registrarLog("Sin conexión con Gateway: Préstamo guardado en cola.", "ERROR");
      setColaPendiente(prev => [...prev, { idAlumno: alId, idLibro: libId, idemKey }]);
    }
  };

  const handlePrestar = (e) => {
    e.preventDefault();
    const idemKey = `IDEM-${Date.now()}-${Math.random().toString(36).substring(2, 7)}`;
    ejecutarPrestamo(parseInt(idAlumno), parseInt(idLibro), idemKey);
    setIdAlumno('');
    setIdLibro('');
  };

  const handleRegistrarAlumno = async (e) => {
    e.preventDefault();
    const res = await fetch(`${API_URL}/alumnos`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ id: parseInt(nuevoIdAlumno), nombre: nuevoNombreAlumno, universidad: nuevaUniversidad })
    });
    if (res.ok) {
      registrarLog(`Alumno ${nuevoNombreAlumno} registrado.`, "EXITO");
      setNuevoIdAlumno(''); setNuevoNombreAlumno(''); setNuevaUniversidad('');
      consultarSaludYDatos();
    }
  };

  const handleRegistrarLibro = async (e) => {
    e.preventDefault();
    const res = await fetch(`${API_URL}/libros`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ id: parseInt(nuevoIdLibro), titulo: nuevoTituloLibro })
    });
    if (res.ok) {
      registrarLog(`Libro "${nuevoTituloLibro}" agregado.`, "EXITO");
      setNuevoIdLibro(''); setNuevoTituloLibro('');
      consultarSaludYDatos();
    }
  };

  const handleLiberar = async (idLibroALiberar) => {
    const res = await fetch(`${API_URL}/libros/${idLibroALiberar}/liberar`, { method: "PUT" });
    if (res.ok) {
      registrarLog(`Libro ID ${idLibroALiberar} devuelto al catálogo.`, "EXITO");
      consultarSaludYDatos();
    }
  };

  return (
    <div className="container">
      <header className="main-header">
        <h1>Biblioteca Distribuidora — Arquitectura de Microservicios</h1>
        <p>Service Discovery • Circuit Breaker • Idempotencia • PWA</p>
      </header>

      {/* Panel de Salud y Descubrimiento en Vivo */}
      <section className="panel-section">
        <h2>Estado del Clúster (Heartbeat cada 2s) &bull; Sistema: {salud.estado} &bull; Disyuntor: {salud.disyuntor}</h2>
        <div style={{ display: 'flex', gap: '10px', justifyContent: 'center', flexWrap: 'wrap' }}>
          {['registro', 'alumnos', 'libros', 'prestamos'].map(srv => (
            <span key={srv} className={salud.servicios?.[srv] ? "status-tag status-available" : "status-tag status-borrowed"}>
              {srv.toUpperCase()}: {salud.servicios?.[srv] ? "EN LÍNEA" : "CAÍDO"}
            </span>
          ))}
          {colaPendiente.length > 0 && (
            <span className="status-tag status-borrowed">EN COLA: {colaPendiente.length}</span>
          )}
        </div>
      </section>

      {/* Formulario Préstamo */}
      <section className="panel-section">
        <h2>Solicitar Préstamo (Con Cola Offline e Idempotencia)</h2>
        <form onSubmit={handlePrestar} className="form-row">
          <div className="field-group">
            <label>ID Alumno</label>
            <input type="number" placeholder="101" value={idAlumno} onChange={(e) => setIdAlumno(e.target.value)} required />
          </div>
          <div className="field-group">
            <label>ID Libro</label>
            <input type="number" placeholder="1" value={idLibro} onChange={(e) => setIdLibro(e.target.value)} required />
          </div>
          <button type="submit" className="btn btn-primary">Registrar Préstamo</button>
        </form>
      </section>

      {/* Historial de Préstamos del Microservicio 7001 */}
      <section className="panel-section">
        <h2>Historial de Préstamos Emitidos (Microservicio :7001)</h2>
        <table className="data-table">
          <thead>
            <tr><th>Folio</th><th>Alumno</th><th>Libro</th><th>Fecha</th></tr>
          </thead>
          <tbody>
            {prestamos.length === 0 ? (
              <tr><td colSpan="4" className="text-muted">Aún no se han emitido folios de préstamo.</td></tr>
            ) : (
              prestamos.map(p => (
                <tr key={p.folio}>
                  <td><strong>{p.folio}</strong></td><td>{p.alumno}</td><td>{p.libro}</td><td>{p.fecha}</td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </section>

      <div className="columns-grid">
        <section className="panel-section">
          <h2>Catálogo de Libros (:6001)</h2>
          <table className="data-table">
            <thead><tr><th>ID</th><th>Título</th><th>Estado</th><th>Acción</th></tr></thead>
            <tbody>
              {libros.map(l => (
                <tr key={l.id}>
                  <td>{l.id}</td><td>{l.titulo}</td>
                  <td><span className={l.disponible ? "status-tag status-available" : "status-tag status-borrowed"}>{l.disponible ? "Disponible" : "Prestado"}</span></td>
                  <td>{!l.disponible ? <button className="btn btn-small btn-danger" onClick={() => handleLiberar(l.id)}>Devolver</button> : "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="form-subbox">
            <h3>Alta de Libro</h3>
            <form onSubmit={handleRegistrarLibro} className="form-row">
              <input type="number" placeholder="ID" value={nuevoIdLibro} onChange={(e) => setNuevoIdLibro(e.target.value)} required />
              <input type="text" placeholder="Título" value={nuevoTituloLibro} onChange={(e) => setNuevoTituloLibro(e.target.value)} required />
              <button type="submit" className="btn btn-secondary">Agregar</button>
            </form>
          </div>
        </section>

        <section className="panel-section">
          <h2>Padrón de Alumnos (:8001)</h2>
          <table className="data-table">
            <thead><tr><th>ID</th><th>Nombre</th><th>Institución</th></tr></thead>
            <tbody>
              {alumnos.map(a => (
                <tr key={a.id}><td>{a.id}</td><td>{a.nombre}</td><td>{a.universidad}</td></tr>
              ))}
            </tbody>
          </table>
          <div className="form-subbox">
            <h3>Alta de Alumno</h3>
            <form onSubmit={handleRegistrarAlumno} className="form-row">
              <input type="number" placeholder="ID" value={nuevoIdAlumno} onChange={(e) => setNuevoIdAlumno(e.target.value)} required />
              <input type="text" placeholder="Nombre" value={nuevoNombreAlumno} onChange={(e) => setNuevoNombreAlumno(e.target.value)} required />
              <input type="text" placeholder="Universidad" value={nuevaUniversidad} onChange={(e) => setNuevaUniversidad(e.target.value)} required />
              <button type="submit" className="btn btn-secondary">Registrar</button>
            </form>
          </div>
        </section>
      </div>

      <section className="panel-section">
        <h2>Bitácora de Eventos del Sistema</h2>
        <div className="terminal-window">
          {logs.map((log, i) => (
            <div key={i} className={`log-line ${log.includes("[ERROR]") ? "log-error" : log.includes("[EXITO]") ? "log-success" : ""}`}>{log}</div>
          ))}
        </div>
      </section>
    </div>
  );
}