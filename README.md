# Lab Rumiología UTEQ

Aplicación Android para identificar equipos del Laboratorio de Rumiología de la UTEQ y consultar información técnica mediante voz o texto.

## Funciones principales

- Detección de equipos en tiempo real con CameraX y un modelo YOLO convertido a TensorFlow Lite.
- Visualización simultánea de hasta tres equipos con cajas delimitadoras independientes.
- Fichas técnicas con descripción, características, seguridad, limpieza y operación.
- Consultas por texto y reconocimiento de voz.
- Respuestas breves reproducidas mediante la síntesis de voz de Android.
- Consulta documental con OpenAI File Search y búsqueda web como respaldo.
- Almacenamiento cifrado de la clave personal mediante Android Keystore.

## Arquitectura

```text
Cámara del teléfono
        │
        ▼
Modelo TensorFlow Lite
        │
        ▼
Equipo identificado ──► Ficha técnica
        │
        ▼
OpenAI Responses API
        │
        ├── File Search
        ├── Guías incluidas en la aplicación
        └── Búsqueda web de respaldo
```

La inferencia visual se ejecuta directamente en el teléfono. La aplicación no necesita un servidor propio ni que una computadora permanezca encendida.

## Requisitos

- Android Studio con soporte para Android SDK 37.
- JDK 11 o una versión compatible configurada por Android Studio.
- Dispositivo Android 8.0 o superior.
- Conexión a Internet para las consultas del asistente.
- Una API key válida de OpenAI proporcionada por cada usuario.

## Configuración de la API key

La clave se introduce desde el botón de configuración de la aplicación. Se cifra con Android Keystore y permanece en el dispositivo.

No se debe escribir ninguna clave en el código fuente. El archivo `local.properties` está excluido del repositorio y se utiliza únicamente para configuración local de Android Studio.

## Compilación

En Windows:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

El APK de depuración se genera en:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Estructura del proyecto

```text
app/       Aplicación Android, recursos, modelo y guías empaquetadas
ml/        Dataset, configuración de Label Studio y herramientas de entrenamiento
backend/   Fuentes documentales y utilidades administrativas de File Search
docs/      Documentación del dataset y evidencias del proyecto
```

La APK realiza sus consultas directamente a OpenAI. El directorio `backend` no es necesario durante la ejecución; conserva las fuentes documentales y las herramientas administrativas utilizadas para preparar los vector stores.

## Entrenamiento del detector

Las imágenes se etiquetan en Label Studio con una caja ajustada al cuerpo completo de cada equipo. Las fotografías sin equipos deben permanecer sin cajas para funcionar como ejemplos negativos.

Flujo recomendado:

1. Exportar las anotaciones en formato YOLO.
2. Validar que cada imagen tenga la clase y la caja correctas.
3. Dividir el conjunto entre entrenamiento, validación y prueba.
4. Entrenar el modelo YOLO.
5. Exportar el mejor peso a TensorFlow Lite.
6. Copiar el modelo final a `ml/models/model.tflite` y compilar nuevamente.

Los comandos auxiliares se encuentran en `ml/scripts/` y la definición de clases en `ml/dataset/data.yaml`.

## Seguridad

- Las credenciales no se incluyen en Git ni dentro del APK.
- La copia de seguridad de datos de la aplicación está deshabilitada.
- Cada instalación utiliza la clave configurada por su propietario.
- Los identificadores de vector stores no conceden acceso sin una API key autorizada.
- Antes de publicar una versión se deben ejecutar las pruebas y revisar que no existan secretos en los archivos versionados.

## Institución

Laboratorio de Rumiología — Universidad Técnica Estatal de Quevedo (UTEQ).
