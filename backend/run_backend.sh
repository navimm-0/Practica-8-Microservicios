#!/bin/bash
javac *.java || exit 1

if [ "$1" == "separado" ]; then
  echo "Abriendo cada microservicio en una pestaña/proceso independiente..."
  gnome-terminal -- bash -c "java ServidorRegistro; exec bash" 2>/dev/null || (java ServidorRegistro &)
  sleep 1
  gnome-terminal -- bash -c "java ServidorAlumnos; exec bash" 2>/dev/null || (java ServidorAlumnos &)
  gnome-terminal -- bash -c "java ServidorLibros; exec bash" 2>/dev/null || (java ServidorLibros &)
  gnome-terminal -- bash -c "java ServidorPrestamos; exec bash" 2>/dev/null || (java ServidorPrestamos &)
  gnome-terminal -- bash -c "java ServidorGateway; exec bash" 2>/dev/null || (java ServidorGateway &)
else
  java ServidorRegistro & P1=$!
  sleep 1
  java ServidorAlumnos & P2=$!
  java ServidorLibros & P3=$!
  java ServidorPrestamos & P4=$!
  java ServidorGateway & P5=$!
  echo "[+] 5 Microservicios activos (Registro :5100, Alumnos :8001, Libros :6001, Préstamos :7001, Gateway :8080)"
  echo "Tip: Para probar tolerancia a fallos mata un proceso con: kill $P4 (mata Préstamos)"
  trap "kill $P1 $P2 $P3 $P4 $P5 2>/dev/null; exit" INT
  wait
fi