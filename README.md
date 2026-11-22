# Lista de la compra

App Android para tener **la misma lista de la compra en tu móvil y en el de tu mujer**. Lo que añade uno aparece al momento en el otro.

- **Varias listas** (Compra, Gadis, Farmacia…), cada una con su número de cosas pendientes.
- **Ordenada por secciones del súper** (fruta, carne, lácteos, limpieza…). La sección se pone sola, y podéis cambiar el orden para que coincida con los pasillos de vuestro súper.
- **Cantidad y quién lo añadió**: «Leche · 2 · Añadido por Suso». Al tachar, pone quién lo cogió.
- **Habituales**: se van apuntando solos. Con el botón ⭐ añadís lo de siempre con un toque.
- **📷 Foto de una lista escrita a mano**: la lee Gemini (la IA de Google), y antes de añadir nada te deja revisar y corregir.
- **🎤 Dictado**: «un litro de aceite, dos paquetes de macarrones y leche» → tres productos con su cantidad.
- Si no hay cobertura en el súper, sigue funcionando y guarda los cambios hasta que vuelva la conexión.
- **Se actualiza sola desde el móvil**: cuando hay una versión nueva, sale un aviso (también una notificación) con el botón **Actualizar**.

Hay que hacer tres cosas **una sola vez**: crear la base de datos (Firebase), sacar la clave de Gemini y compilar el APK.

---

## 1. Crear la base de datos en Firebase (gratis, unos 10 minutos)

1. Entra en **console.firebase.google.com** con tu cuenta de Google.
2. **Crear un proyecto** → nombre, por ejemplo `lista-compra` → puedes desactivar Google Analytics → **Crear proyecto**.
3. En el menú de la izquierda: **Compilación (Build) → Realtime Database → Crear base de datos**.
   - Ubicación: **Bélgica (europe-west1)**.
   - Reglas: **Empezar en modo bloqueado** → Habilitar.
4. Pestaña **Reglas**: borra lo que hay, pega esto y pulsa **Publicar**:

   ```json
   {
     "rules": {
       ".read": false,
       ".write": false,
       "hogares": {
         "$casa": {
           ".read": "$casa.length >= 20",
           ".write": "$casa.length >= 20"
         }
       }
     }
   }
   ```

   Así nadie puede ver la base de datos entera: solo quien tenga el código de vuestra casa (24 letras al azar que genera la app).
5. Pestaña **Datos**: arriba sale la dirección, algo como
   `https://lista-compra-1234-default-rtdb.europe-west1.firebasedatabase.app`. **Cópiala**: la pedirá la app.

El plan gratuito (Spark) da de sobra para dos personas. No hace falta poner tarjeta.

## 2. Sacar la clave de Gemini (gratis, 2 minutos) — para leer fotos

1. Entra en **aistudio.google.com** con tu cuenta de Google.
2. **Get API key → Create API key**. Copia la clave (empieza por `AIza…`).
3. En la app: **⚙ Ajustes → Clave de Gemini** → pégala → **Probar Gemini**.

Sin la clave, la app funciona igual, pero la cámara no lee fotos y el dictado separa los productos de forma más sencilla. Con la clave, Gemini también ordena lo dictado aunque no hagas pausas.

> En el nivel gratuito, Google puede usar lo que se envía para mejorar sus productos. Aquí solo se envían fotos de listas de la compra y frases dictadas.
> Si algún día Gemini avisa de que se ha llegado al límite gratuito, basta con esperar un minuto.

## 3. Compilar el APK (con GitHub, como Bus Arteixo y Teclado TV)

1. Crea un repositorio nuevo en GitHub, por ejemplo `ListaCompra`.
2. Sube **todo el contenido** de esta carpeta (incluida la carpeta `.github`, que a veces se queda fuera por empezar por punto).
   Antes, añade los dos secretos de la firma (ver «La firma»).
3. Pestaña **Actions** → el trabajo *Compilar APK* se lanza solo (o pulsa *Run workflow*).
4. Cuando termine (3-5 min), entra en el trabajo y descarga **ListaCompra-apk** (un .zip con `ListaCompra.apk`).

Pasa el APK a los dos móviles e instálalo (Android pedirá permitir «instalar apps desconocidas»). **Solo hace falta esta vez**: a partir de aquí, la app se actualiza sola (ver «Actualizaciones»).

### La firma (secretos de GitHub)

Para que Android acepte cada versión nueva encima de la anterior, todas van firmadas con la misma clave. Esa clave **no está en el repositorio** (que es público): está guardada en dos secretos de GitHub, en **Settings → Secrets and variables → Actions**:

| Secreto | Qué es |
|---|---|
| `KEYSTORE_B64` | la clave de firma, en texto (base64) |
| `KEYSTORE_PASS` | su contraseña |

Guarda una copia de los dos en un sitio seguro (por ejemplo, en Google Drive en una carpeta privada). Si se pierden, las versiones nuevas no podrán instalarse encima y habría que desinstalar y volver a instalar la app.

## 4. Ponerla en marcha

**En tu móvil:**
1. Escribe tu nombre → **🏠 Sí: crear nuestra lista** → pega la dirección de Firebase → **Crear**.
2. Pon la clave de Gemini en ⚙ Ajustes (así irá también en el código para tu mujer).
3. **⚙ Ajustes → 📤 Enviar el código** → mándalo a tu mujer por WhatsApp.

**En el móvil de tu mujer:**
1. Copia el mensaje de WhatsApp entero.
2. Abre la app → su nombre → **🔗 No: tengo un código** → **📋 Pegar** → **Unirme**.

Desde ese momento veis la misma lista.

> El código da acceso a vuestra lista: no lo publiquéis. Si se escapa: ⚙ Ajustes → «Cambiar a un código nuevo» (se llevan las listas al código nuevo) y vuelve a enviárselo a tu mujer.

## Cómo se usa

| Quiero… | Cómo |
|---|---|
| Añadir algo | Escríbelo arriba y pulsa Intro. Varias cosas a la vez separadas por comas: `2 leche, pan de molde, 1 kg de tomates` |
| Dictar | 🎤 y habla: «dos barras de pan, un litro de aceite y lejía» |
| Pasar una lista en papel | 📷 → Hacer la foto (o elegirla de la galería) → revisa → **Añadir** |
| Tacharlo en el súper | Toca el producto. Pasa a «En el carro» |
| Limpiar lo comprado | «En el carro» → **🗑 Quitar del todo** (se puede deshacer) |
| Cambiar cantidad o sección, o moverlo a otra lista | El lápiz ✎ |
| Añadir lo de siempre | ⭐ Habituales → toca los que falten (verde = ya está en la lista) |
| Nueva lista (Gadis, Farmacia…) | Arriba, **＋ Lista** |
| Renombrar, ordenar o borrar una lista | Toca la lista que ya está seleccionada |
| Poner las secciones en el orden de los pasillos | ⚙ Ajustes → Orden de las secciones |
| Letra más grande | ⚙ Ajustes → Tamaño de letra |

Arriba a la derecha se ve el estado: **● al día**, **○ sin conexión** (los cambios esperan y se envían solos) o **⚠ error**.

## Actualizaciones

Cada vez que se cambia algo en GitHub, se compila solo y se publica como versión nueva en **Releases** (1.4, 1.5…). El mensaje del cambio sale como explicación de la versión.

- Al abrir la app (y cada 12 horas en segundo plano), mira si hay versión nueva. Si la hay, sale un aviso arriba y una **notificación**.
- Pulsa **Actualizar**: se descarga y Android pregunta «¿Quieres actualizar esta app?» → **Actualizar**. No se pierde nada.
- La primera vez Android pide permitir que «Lista de la compra» instale apps: actívalo y vuelve atrás, y la instalación sigue sola.
- Para mirarlo a mano: ⚙ Ajustes → **Buscar actualizaciones**.

Android siempre pide confirmar la instalación: ninguna app puede actualizarse en silencio si no viene de Google Play.

## Si algo no funciona

**⚙ Ajustes → Registro de pruebas → Prueba completa → Copiar todo**, y pega el texto en el chat con Claude. La clave de Gemini sale tapada con `***`.

- **⚠ error / «permiso denegado»**: casi siempre son las reglas de Firebase (paso 1.4) mal pegadas o sin publicar.
- **El 🎤 no hace nada**: el dictado usa la app de Google del móvil, así que tiene que estar instalada y actualizada.
- **Gemini dice «modelo no encontrado»**: la app busca sola otro modelo y lo guarda. Se puede ver y cambiar en Ajustes → Modelo.

## Cómo funciona por dentro

```
hogares/<código de 24 letras>/
  listas/<id>        { nombre, orden }
  items/<lista>/<id> { nombre, cantidad, seccion, por, hecho, hechoPor, cuando, creado }
  habituales/<clave> { nombre, seccion, veces, ultima }
  ajustes/orden      "fruta,carne,…"   (orden de las secciones, compartido)
```

- Se habla con Firebase por su API REST. Para los cambios en directo, la app deja abierta una conexión de «streaming» mientras está en pantalla. Al salir de la app la cierra para no gastar batería.
- Cada cambio se aplica primero en el móvil y luego se envía. Si no hay red, queda en cola (guardada) y se reintenta cada 15 s.
- Gemini recibe la foto reducida a 1800 px y devuelve JSON con nombre, cantidad y sección. También recibe la lista de habituales, para que use los mismos nombres que soléis usar.

```
app/src/main/assets/index.html                          toda la pantalla y la lógica (HTML + JavaScript)
app/src/main/java/com/suso/listacompra/MainActivity.java   puente: internet, directo, cámara, voz, compartir
app/src/main/java/com/suso/listacompra/Actualizaciones.java  aviso de versiones nuevas en segundo plano
.github/workflows/build.yml                               compila el APK, lo firma con los secretos y lo publica en Releases
```

`index.html` se puede abrir en el navegador del ordenador para probarlo: botón «Probar sin conexión», o con la dirección real de Firebase.
