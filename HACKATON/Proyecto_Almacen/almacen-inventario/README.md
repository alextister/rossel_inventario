# Servicio de inventario — Almacén inteligente para taller

Servicio en Java que consume los eventos del taller, guarda el inventario como un **libro mayor de movimientos**
(SQLite) y expone existencias, reservas y faltantes por API HTTP y por el tópico `inventory.events`.

## Requisitos
- JDK 25+ y Maven 3.8+
- Docker (para el kit: Redpanda + simulador + pruebas de contrato)

## Estructura
```
pom.xml
src/main/resources/schema.sql     tablas (libro mayor, saldos, necesidades, reservas, faltantes, outbox...)
src/main/java/taller/inventario/
  Main.java            arranque y opciones
  EventSource          de dónde vienen los eventos (FileEventSource | KafkaEventSource)
  Processor.java       idempotencia + reglas por evento + eventos aparcados (desorden)
  Stock.java           movimientos, necesidades por línea, reservas, faltantes, surtido y liberación
  Floor.java           salida contra orden, transferencia, conteo cíclico
  Outbox.java          eventos de salida (se guardan en la misma transacción)
  Api.java             rutas HTTP (según contract/openapi.yaml)
```

## Con Kafka (necesario para las pruebas de contrato y la demo)
```bash
docker compose up -d            # en la carpeta del kit
mvn -q compile exec:java -Dexec.args="--kafka localhost:19092 --reset"
```
- Carga primero `shop.catalog` completo y después los 4 tópicos (regla 22 del contrato).
- `--reset` borra la base y relee los tópicos desde el inicio (así se prueba la reconstrucción).
  Sin `--reset` continúa donde se quedó.

Pruebas de contrato (con el servicio escuchando en el puerto 8000):
```bash
docker compose run --rm contract-tests --api http://host.docker.internal:8000
```

## Sin broker (solo para desarrollar)
Copia `data/events.jsonl` del kit a esta carpeta y corre:
```bash
mvn -q compile exec:java -Dexec.args="--file data/events.jsonl --reset"
```
Los eventos de salida se escriben en `out/inventory.events.jsonl` (y los fallidos en `out/inventory.dlq.jsonl`).
Valídalos con `uv run --with jsonschema tools/validate_events.py out/inventory.events.jsonl` (desde el kit).

## Opciones
`--file ruta` · `--kafka host:puerto` · `--reset` · `--db inventario.db` · `--port 8000` · `--out out`

## Rutas (ver contract/openapi.yaml)
GET `/parts/{id}/availability` · GET `/parts/{id}/ledger` · GET `/work-orders/{code}/materials` ·
GET `/shortages?work_order_code=&part_id=` · GET `/reorder-suggestions` · GET `/unmatched-receipts` ·
POST `/unmatched-receipts/{id}/resolve` · POST `/issues` · POST `/transfers` · POST `/counts` ·
extra: PUT `/parts/{id}/policy` (`min_qty`, `max_qty`, `reorder_qty`, `lead_time_days`).

## Pantallas (http://localhost:8000)
- `/` Almacén: existencias por pieza y ubicación, faltantes, sugerencias de compra y recepciones sin relacionar.
- `/orden.html` Orden de trabajo: busca por código y muestra requerido, reservado, entregado y pendiente.
- `/piso.html` Piso (celular): escanear ubicación y pieza para retirar, transferir o contar.

La cámara del celular solo funciona en un origen seguro (HTTPS o localhost). Opciones: abrir la página
con `adb reverse tcp:8000 tcp:8000` (USB) y entrar a `http://localhost:8000/piso.html`; o usar un túnel HTTPS
(por ejemplo `cloudflared tunnel --url http://localhost:8000`). Un lector USB de código de barras también sirve:
escribe el código y termina con Enter.
