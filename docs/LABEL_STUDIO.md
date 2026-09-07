# Etiquetado con Label Studio — Laboratorio de Rumiología (UTEQ)

Este proyecto cumple el enunciado así:

1. **YOLO** identifica el equipo en la cámara (bounding box + clase).
2. **Label Studio** sirve para dibujar los recuadros y exportar el dataset YOLO.
3. **RAG** usa las guías del equipo detectado para el asistente.

> **Sobre “YOLOv15” del enunciado:** Ultralytics no publica un checkpoint `yolov15.pt`.
> Usamos **YOLO26** (familia YOLO actual, misma técnica y mismo formato de etiquetas).
> En el informe escriba: *“Se implementó detección con la familia YOLO (Ultralytics YOLO26),
> equivalente funcional a lo solicitado como YOLOv15.”*

---

## Atajo actual (recomendado)

Si ya tiene fotos en `ml/labelstudio/images/`:

```bash
python ml/scripts/build_yolo_dataset.py
python ml/scripts/package_colab_dataset.py
```

Esto genera cajas propuestas (mejores que el bootstrap centrado), composiciones multi-equipo,
`yolo_ls` con las 11 clases y el ZIP para Colab. Luego revise/ajuste en Label Studio y entrene con
`ml/notebooks/train_yolo_colab.ipynb`. Artefactos → `python ml/scripts/integrate_colab_artifacts.py`.

---

## Requisitos

```bash
pip install label-studio ultralytics pyyaml pillow
```

---

## Paso 1 — Copiar fotos del laboratorio

Tus fotos están en `Modelos lab` (carpetas MAQUINA 1 … MAQUINA 12).
El mapeo a clases YOLO está en `ml/dataset/inventario_equipos.json`.

```powershell
cd C:\Users\Dispositivo\AndroidStudioProjects\deteccionderostro

python ml/scripts/sync_photos_from_inventario.py --src "C:\Users\TU_USUARIO\OneDrive\Desktop\Modelos lab"
```

Resultado: `ml/dataset/raw/<clase_yolo>/*.jpg` (11 clases).

---

## Paso 2 — Etiquetas iniciales (bootstrap)

Genera un recuadro centrado por foto para acelerar el trabajo en Label Studio:

```powershell
python ml/scripts/auto_label_from_folders.py
```

Resultado: `ml/dataset/all/images/` + `ml/dataset/all/labels/*.txt`

Formato YOLO por línea: `class_id cx cy width height` (valores 0–1).

---

## Paso 3 — Abrir Label Studio

```powershell
python ml/scripts/prepare_labelstudio.py
label-studio start
```

Se abre en: http://localhost:8080

### Configurar el proyecto

1. **Create Project** → nombre: `Lab Rumiología`
2. **Labeling Setup** → *Computer Vision* → *Object Detection with Bounding Boxes*
3. Borre la plantilla y pegue el contenido de `ml/labelstudio/config.xml`
4. **Save**

### Importar imágenes

**Opcion A (recomendada si falla el arrastre): Almacenamiento local**

1. Cierre Label Studio si esta abierto.
2. Inicie con el script del proyecto (habilita archivos locales):

```powershell
ml\scripts\start_labelstudio.bat
```

3. En su proyecto: **Settings -> Cloud Storage -> Add Source Storage -> Local files**
   - **Storage Title:** `lab_images`
   - **Absolute local path:**
     `C:\Users\Dispositivo\AndroidStudioProjects\deteccionderostro\ml\labelstudio\images`
   - **Import method:** `Files`
   - **File Filter Regex:** `.*\.(jpg|jpeg|png)`
4. Guarde y pulse **Sync Storage** (importa las 320 fotos sin subirlas por el navegador).

**Opcion B: Importar por lotes (script)**

1. En Label Studio: usuario -> **Account & Settings** -> copie su **Access Token**.
2. Ejecute:

```powershell
python ml/scripts/import_to_labelstudio.py --token SU_TOKEN --project 1 --batch 15
```

**Opcion C: Arrastrar manualmente**

Suba de **10 en 10** fotos (no las 320 juntas). Si arrastra todas, sale el error
`You cannot access body after reading from request's data stream`.

**Opcion D:** arrastre las fotos de `ml\labelstudio\images\` (import manual en lotes pequenos).

### Etiquetar (lo importante)

Por cada imagen:

1. Revise el recuadro (si hay pre-anotación, ajústelo).
2. Si faltan equipos, dibuje un **nuevo recuadro** por cada uno visible.
3. Elija la **clase** (nombre del equipo) en la lista.
4. Pulse **Submit** (o Ctrl+Enter).

Reglas:

- **Un recuadro = un equipo** visible en la foto.
- La **clase** es el equipo (`ankom_a200`, `ohaus_pr`, etc.).
- Puede haber **varios recuadros** en una misma foto.
- Corrija cajas mal puestas; no deje fotos sin submit.

---

## Paso 4 — Exportar desde Label Studio

1. **Export** → formato **YOLO**
2. Guarde el ZIP como:

```
ml/dataset/exports/labelstudio_yolo.zip
```

Debe contener al menos `labels/` y `classes.txt`. Si usó **Local Storage**,
el ZIP a menudo **no incluye** `images/` (solo las etiquetas).

---

## Paso 5 — Importar al repo y dividir 70/15/15

```powershell
python ml/scripts/import_labelstudio.py --src ml/dataset/exports/labelstudio_yolo.zip --split
```

Si el export no trae imágenes, el script las toma de `ml/labelstudio/images` (por defecto).
También puede indicar otra carpeta con `--images`.

```powershell
python ml/scripts/import_labelstudio.py --src ml/dataset/exports/labelstudio_yolo.zip --images ml/labelstudio/images --split
```

Genera:

```
ml/dataset/images/train|val|test/
ml/dataset/labels/train|val|test/
```

Comprobar conteos:

```powershell
python ml/scripts/count_dataset.py --split
```

---

## Paso 6 — Entrenar YOLO y exportar a Android

```powershell
python ml/scripts/train_yolo.py --data ml/dataset/data.yaml --epochs 80 --imgsz 512
python ml/scripts/export_tflite.py --weights ml/models/best.pt
```

Luego en Android Studio: **Run** para instalar el APK con el modelo nuevo.

---

## Clases YOLO (11 equipos)

| id | clase | Equipo |
|----|-------|--------|
| 0 | ankom_daisy_ii | Incubadora ANKOM Daisy II |
| 1 | aquasearcher | OHAUS AquaSearcher |
| 2 | ohaus_pr | Balanza OHAUS PR |
| 3 | contador_colonias | Contador de colonias |
| 4 | ohaus_pa214 | Balanza OHAUS PA214 |
| 5 | banio_maria_memmert | Baño María Memmert |
| 6 | ankom_a200 | ANKOM 200 Fiber Analyzer |
| 7 | shimadzu_gc2014 | Cromatógrafo Shimadzu GC-2014 |
| 8 | estufa_secado | Estufa de secado |
| 9 | selladora_aie200 | Selladora AIE-200 |
| 10 | desecador | Desecador |

Deben coincidir con `ml/labelstudio/config.xml`, `data.yaml` y `labels.txt` de la app.

---

## Checklist antes de entregar

- [ ] Fotos reales del lab (no sintéticas)
- [ ] Etiquetado en Label Studio revisado a mano
- [ ] Export YOLO importado con `--split`
- [ ] `count_dataset.py --split` con todas las clases con datos
- [ ] Modelo entrenado y APK probado en teléfono
- [ ] Evidencias en `docs/evidencias/`

---

## Problemas frecuentes

| Problema | Solución |
|----------|----------|
| No encuentra `Modelos lab` | Use `--src` con la ruta completa |
| Clase desconocida al importar | El nombre en Label Studio debe ser exacto (`ohaus_pr`, no `Ohaus PR`) |
| Pocas detecciones en el teléfono | Re-etiquete con más variedad de ángulos y re-entrene |
| Export vacío | Asegúrese de haber hecho **Submit** en cada imagen |
| Importadas 0 imágenes | El ZIP YOLO no trae fotos; use `--images ml/labelstudio/images` |
| Permission denied al entrenar | Cierre visores de fotos / OneDrive; vuelva a importar con `--split` |
