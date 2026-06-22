# uTorrent ICI-4344 — Sistema Distribuido P2P para Distribución de Archivos

Proyecto Final del curso **ICI-4344 Computación Paralela y Distribuida** (PUCV).

El sistema evoluciona un cliente BitTorrent cliente-servidor hacia una arquitectura
**genuinamente distribuida**: un **clúster de tres o más nodos de directorio** (trackers
replicados) que se coordinan **sin reloj físico común** y siguen dando servicio aunque
caiga cualquiera de sus nodos.

Mecanismos distribuidos implementados:

- **Topología multinodo** (≥3 nodos, arquitectura multiservidor con membresía).
- **Reloj lógico de Lamport** para el ordenamiento causal de eventos.
- **Exclusión mutua distribuida** con **Ricart-Agrawala** (protege la escritura del directorio).
- **Elección de coordinador** con el **algoritmo del abusón (Bully)**.
- **Tolerancia a fallos**: detección por latidos (crash/omisión) y recuperación por
  reelección y recálculo de quórum, sin interrupción del servicio.
- **Generador de carga** con métricas (throughput, latencia media/p95, tasa de error) y
  falla inducida del coordinador.

---

## 1. Requisitos

- **JDK 17 o superior** (`javac` y `java`). Verifícalo con:

  ```
  java -version
  javac -version
  ```

  Si aparece `java` pero no `javac`, tienes solo el JRE: instala el JDK desde
  [adoptium.net](https://adoptium.net) o, en Linux, `sudo apt install openjdk-17-jdk`.

- No se requieren librerías externas: el proyecto usa solo la biblioteca estándar de Java.

---

## 2. Estructura del proyecto

```
src/utorrent/
├── app/         Punto de entrada del cliente P2P y del generador de carga
├── cluster/     Capa de coordinación distribuida (Lamport, Ricart-Agrawala, Bully,
│                detector de fallos, réplica, membresía, registro de eventos)
├── tracker/     Servidor de directorio (clúster), cliente con failover, registro de pares
├── p2p/         Plano de datos entre pares (transferencia, piezas, choke, ensamblado)
├── protocolo/   Mensajes del protocolo entre pares (handshake, bitfield, request, piece…)
├── modelos/     Estructuras de datos (metadatos, info de par, solicitudes/respuestas)
└── utils/       Hash SHA-1, configuración de red, lectura/escritura de bloques
```

Archivos de configuración de ejemplo en la raíz: `cluster.properties.example` y
`tracker.properties.example`. Cópialos sin el sufijo `.example` y ajústalos a tu entorno.

---

## 3. Compilación

Desde la **carpeta raíz** del proyecto (la que contiene `src/`). El flag `-encoding UTF-8`
es necesario porque el código incluye comentarios con tildes.

**Linux / macOS:**

```bash
javac -encoding UTF-8 -d out $(find src -name "*.java")
```

**Windows PowerShell:**

```powershell
javac -encoding UTF-8 -d out (Get-ChildItem -Recurse -Filter *.java -Path src | ForEach-Object { $_.FullName })
```

**Windows CMD:**

```cmd
dir /s /b src\*.java > sources.txt
javac -encoding UTF-8 -d out @sources.txt
```

Si todo está bien, compila **66 clases** en `out/` y no imprime errores.

---

## 4. Configuración del clúster

Cada nodo necesita un archivo **`cluster.properties`** en su carpeta de ejecución. La lista
`nodos` debe ser **idéntica en los tres** y contener la membresía completa en formato
`id@host:puerto`; lo único que cambia entre nodos es `nodo.id`.

`cluster.properties` del **nodo 1** (los nodos 2 y 3 son iguales cambiando solo `nodo.id`):

```properties
nodo.id=1
nodos=1@127.0.0.1:7001,2@127.0.0.1:7002,3@127.0.0.1:7003
```

Para un despliegue en **red de área local real** (recomendado para la demo), usa las IPv4
de cada máquina en lugar de `127.0.0.1`:

```properties
nodo.id=1
nodos=1@192.168.1.10:7001,2@192.168.1.11:7001,3@192.168.1.12:7001
```

El **coordinador inicial** es, por convención, el nodo de mayor `id` (el nodo 3).

---

## 5. Ejecución

### 5.1. Levantar el clúster de tres nodos

Crea una carpeta por nodo (`nodo1`, `nodo2`, `nodo3`), pon en cada una su
`cluster.properties`, y levanta cada tracker en una **terminal independiente**. Al arrancar,
cada tracker pide por consola el **máximo de peers por IP** y el **tamaño del pool de hilos**.

**Linux / macOS** (desde la carpeta de cada nodo):

```bash
java -cp ../out utorrent.tracker.ServidorTracker
```

**Windows PowerShell:**

```powershell
java -cp ..\out utorrent.tracker.ServidorTracker
```

Los tres nodos se descubren por la membresía, intercambian latidos y acuerdan al nodo 3
como coordinador. Cada nodo escribe su bitácora en `logs/eventos-nodoN.csv` y `.log`.

### 5.2. Demo de transferencia P2P (función F2)

Con el clúster en marcha, abre dos terminales más para un **sembrador (seeder)** y una
**sanguijuela (leecher)**. Indica los nodos del clúster con la variable `TRACKER_HOSTS`
(o un archivo `tracker.properties` con `tracker.hosts=...`).

**Sembrador** (publica un archivo y queda sirviéndolo):

```bash
TRACKER_HOSTS="127.0.0.1:7001,127.0.0.1:7002,127.0.0.1:7003" \
  java -cp out utorrent.app.AplicacionCliente
# Menú → opción 1 (Compartir). Puerto de escucha sugerido: 6881.
```

**Sanguijuela** (descubre el archivo por el directorio y lo descarga):

```bash
TRACKER_HOSTS="127.0.0.1:7001,127.0.0.1:7002,127.0.0.1:7003" \
  java -cp out utorrent.app.AplicacionCliente
# Menú → opción 2 (Descargar). Puerto de escucha sugerido: 6882.
```

La descarga se verifica pieza a pieza con SHA-1. Para confirmar que el archivo llegó
íntegro, compara los hashes del original y del descargado:

```bash
sha1sum archivos-a-compartir/<archivo>  descargas/<archivo>   # Linux/macOS
Get-FileHash descargas\<archivo> -Algorithm SHA1              # PowerShell
```

### 5.3. Prueba de carga con falla inducida (requisitos 3.1–3.4)

Esta es la prueba central. Conviene **acelerar el detector** y **elevar el umbral
anti-Sybil** (si no, el control de admisión rechazaría el tráfico masivo desde una misma IP).

**1) Levanta los tres nodos** con detector acelerado para una recuperación ágil. Cuando
pidan el máximo de peers por IP, responde un valor alto (p. ej. `100000000`) y un pool de `64`:

```bash
java -Ddetector.intervalo=500 -Ddetector.sospecha=1200 -Ddetector.caida=2000 \
     -Dbully.timeoutOk=500 -Dbully.timeoutCoord=1000 \
     -cp ../out utorrent.tracker.ServidorTracker
```

**2) Lanza el generador de carga** (50 clientes, 60 s, con la falla programada en t = 30 s):

```bash
TRACKER_HOSTS="127.0.0.1:7001,127.0.0.1:7002,127.0.0.1:7003" \
  java -Dcarga.hilos=50 -Dcarga.duracion=60 -Dcarga.fallaEn=30 \
       -cp out utorrent.app.GeneradorCarga
```

**3) En el segundo 30**, en plena carga, **derriba el proceso del coordinador** (el nodo de
mayor id, el nodo 3). Cierra esa terminal con `Ctrl+C`, o:

```bash
# Linux/macOS:  matar abruptamente el proceso del coordinador
kill -9 <PID_del_nodo3>
# Windows PowerShell:
Stop-Process -Id <PID_del_nodo3> -Force
```

Los nodos supervivientes detectan la ausencia de latidos, declaran la caída, recalculan el
quórum de la exclusión mutua y eligen un nuevo coordinador, **sin dejar de atender** a los
clientes. Al terminar, el generador imprime las métricas globales y su desglose **antes /
después** de la falla.

### 5.4. Recolección de la evidencia

Tras la corrida quedan en `logs/`:

- `eventos-nodo1.csv`, `eventos-nodo2.csv`, `eventos-nodo3.csv` — eventos con marca de
  Lamport (`ts_lamport;ts_epoch_ms;nodo;tipo;detalle`): mensajes de mutex, eventos de
  elección, detección de la falla y líneas `METRICAS` con los recuentos de coordinación.
- `carga.csv` — registro por petición (`ts_ms;latencia_ms;exito`) para construir la serie
  temporal de latencia y el gráfico del impacto de la falla.

Fusionando los tres `eventos-nodoN.csv` y ordenándolos por `ts_lamport` se reconstruye el
orden causal total y se verifica que ningún par de nodos estuvo simultáneamente dentro de la
sección crítica.

---

## 6. Modo de nodo único (compatibilidad con el Parcial)

Si **no** existe `cluster.properties`, el tracker arranca en modo de nodo único y pide el
puerto por consola; el cliente puede usar entonces un único `tracker.properties` con
`tracker.host` / `tracker.port`. Útil para probar solo la transferencia P2P sin clúster.

---

## 7. Solución de problemas

- **`unmappable character` al compilar:** falta `-encoding UTF-8` en el comando de `javac`.
- **El cliente o el generador no conectan:** revisa que los puertos de `cluster.properties`
  (donde escuchan los nodos) coincidan con los de `TRACKER_HOSTS` / `tracker.hosts` (a dónde
  apunta el cliente). Por defecto, ambos son **7001, 7002, 7003**.
- **El generador reporta errores de admisión:** el control anti-Sybil está rechazando el
  tráfico; sube el "máximo de peers por IP" al arrancar los nodos.
- **No se dispara la elección al matar el coordinador:** asegúrate de matar el **proceso del
  nodo de mayor id** (el coordinador), no otro nodo.

## Anexo C — Ejecución real de la prueba de tráfico con falla inducida

Esta corrida se ejecutó sobre Windows + PowerShell con tres nodos de directorio
(`nodo1`/`nodo2`/`nodo3`) en `127.0.0.1:7001-7003`, cada uno en su propia JVM con su
`cluster.properties`, y los detectores acelerados para la demostración
(`-Ddetector.intervalo=500 -Ddetector.sospecha=1200 -Ddetector.caida=2000`,
`-Dbully.timeoutOk=500 -Dbully.timeoutCoord=1000`). El máximo de peers por IP se elevó a
100.000.000 porque los 50 clientes salen todos de `127.0.0.1` y el control anti-Sybil,
con su valor por defecto (3), los rechazaría.

### Procedimiento
1. Arranque de los tres trackers. El coordinador inicial determinista es el nodo 3 (mayor id).
2. Generador de carga: 50 clientes concurrentes durante 60 s
   (`-Dcarga.hilos=50 -Dcarga.duracion=60 -Dcarga.fallaEn=30`).
3. En t ≈ 30 s se derribó el proceso del coordinador (nodo 3) con `Stop-Process -Force`
   (caída abrupta, sin cierre ordenado).

### Resultado de la falla inducida (evidencia en `logs/`)
A los ~2,2 s sin latidos, los nodos supervivientes marcaron CAIDO al nodo 3, lo
descontaron del quórum de la exclusión mutua, dispararon la elección Bully y **el nodo 2
quedó como nuevo coordinador**, sin interrumpir el servicio:
