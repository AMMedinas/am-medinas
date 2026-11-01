# Puente del control blanco · AM Medinas 360

El control blanco de la base Audiotek 49216 tiene antena: es de **radiofrecuencia (normalmente 433 MHz)**, no Bluetooth. Ningún celular puede mandar esa señal, por eso este puente la aprende y la repite cuando la cabina se lo pide.

## Piezas (en tiendas de electrónica o Mercado Libre)
1. **ESP32 DevKit** (con cable USB de datos)
2. **Receptor 433 MHz RXB6** (o similar)
3. **Transmisor 433 MHz FS1000A** (o similar)
4. 6 cables dupont hembra-hembra y 2 cables de 17.3 cm para antenas

**Antes de comprar:** revisa la etiqueta atrás del control blanco. Si dice **315 MHz**, compra receptor y transmisor de 315 MHz (las conexiones son las mismas).

## Conexiones
| Módulo | VCC | GND | DATA |
|---|---|---|---|
| Receptor 433 | 5V (VIN) | GND | GPIO 27 |
| Transmisor 433 | 5V (VIN) | GND | GPIO 26 |

## Cargar el programa
1. Instala **Arduino IDE 2**.
2. En Preferencias, agrega el gestor de tarjetas de Espressif ESP32, e instala **esp32 de Espressif** en el Gestor de tarjetas.
3. En el Gestor de librerías instala **rc-switch** (de sui77).
4. Abre `puente_control_blanco.ino`, elige la placa **ESP32 Dev Module** y el puerto, y pulsa **Subir**.
5. En el Monitor Serie (115200) debe decir **AMM360 Puente listo**. Si presionas un botón del control blanco cerca, aparece su código: así sabes que lo escucha.

## Enlazarlo con la cabina
1. Abre la cabina en Chrome en tu Android: ammedinas.github.io/am-medinas/cabina.html
2. ⚙ Ajustes → **Usar puente del control blanco** → **Conectar motor por Bluetooth** → elige **AMM360 Puente**.
3. Activa **Modo aprender** y, para cada botón de la pantalla: tócalo, y en menos de 10 segundos presiona ese mismo botón en el control blanco apuntando al puente. La cabina dice "aprendido".
4. Desactiva Modo aprender y prueba: ▶ debe encender el giro, ⇄ la reversa, + y − la velocidad, y los botones de luz.

Se aprende una sola vez: el puente lo guarda aunque lo desconectes. Aliméntalo con un cargador USB o el banco de energía del soporte, cerca de la base.

## Notas
* ON/OFF es un mismo botón: la cabina lo presiona al empezar el video y otra vez al terminar. Si la base ya estaba girando, apágala con el control antes de la siguiente toma.
* Este puente funciona con controles de código fijo (los más comunes en estas bases). Si el Monitor Serie no muestra ningún código al presionar el control blanco, el control usa otra frecuencia o un código cambiante y habría que usar otro receptor.
