# Documento de decisiones (borrador, máx. 1 página)

**Relación de compras con el catálogo.** El `part_number` y el `sku` se normalizan (sin espacios y en mayúsculas)
y se comparan por igualdad. Entra solo si coincide con **exactamente una** pieza; si no coincide o el `sku` está en
varias piezas, no entra: se guarda en `unmatched_receipts`, se emite `stock.unmatched_receipt` y se resuelve a mano
(`POST /unmatched-receipts/{id}/resolve`, que registra la entrada y surte faltantes). `customer_order` no emite nada
y solo `restock` entra, siempre en la ubicación 100 (U-100). Al arrancar o reconstruir se carga todo `shop.catalog`
antes que los demás tópicos, para que las compras no lleguen antes que sus piezas.

**Kits y servicios.** Se inventarían los **componentes** (piezas con `part_id`), no el kit: el stock físico se mueve
por pieza y una reserva por kit ocultaría qué componente falta. Una línea de kit sin `part_id` es faltante sin
identificar. Las piezas de familia SERVICIO (cromado, rectificado) no se inventarían: no se reservan ni reciben stock.

**Necesidad y reservas dobles.** La necesidad vive por (orden, `bom_line_id`): una aprobación posterior la reemplaza
(reserva la diferencia o libera el excedente) y las líneas no mencionadas la conservan. Toda operación pasa por una
única conexión y una transacción serializada, y la reserva usa `UPDATE ... WHERE on_hand - reserved >= qty`, así que
dos aprobaciones simultáneas no pueden apartar la misma última pieza ni dejar `available` bajo cero.

**Idempotencia y reconstrucción.** Cada `event_id` se registra en `processed_events` en la misma transacción que sus
cambios y sus eventos de salida; un duplicado no cambia nada ni emite salidas. Los movimientos son inmutables y los
saldos son una proyección. Los IDs de salida se derivan del `event_id` de entrada y las fechas vienen de
`occurred_at`, así que releer los tópicos desde cero da el mismo `on_hand`. Los eventos con dependencias faltantes
(pieza o línea de la lista de partes) se aparcan y se reintentan cuando llega el catálogo; los que fallan tras tres
intentos van a `inventory.dlq`. Limitación conocida: las operaciones manuales por API (salidas, transferencias,
conteos y recepciones resueltas a mano) no viajan por los tópicos de entrada, por lo que una reconstrucción las pierde.
