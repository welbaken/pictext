---
license: apache-2.0
library_name: PaddleOCR
language:
- en
- zh
pipeline_tag: image-to-text
tags:
- OCR
- PaddlePaddle
- PaddleOCR
- layout_detection
---

# PP-DocLayout-S

## Introduction

A high-efficiency layout area localization model trained on a self-built dataset containing Chinese and English papers, magazines, contracts, books, exams, and research reports using PicoDet-S. capable of detecting 23 common categories: document title, paragraph title, text, page number, abstract, table of contents, references, footnotes, header, footer, algorithm, formula, formula number, image, figure caption, table, table caption, seal, figure title, figure, header image, footer image, and aside text. The key metrics are as follow:

| Model| mAP(0.5) (%) | 
|  --- | --- | 
|PP-DocLayout-S |  70.9 | 

**Note**: the evaluation set of the above accuracy indicators is a self built layout area detection data set, including 500 document type images such as Chinese and English papers, newspapers, research papers and test papers.

## Quick Start

### Installation

1. PaddlePaddle

Please refer to the following commands to install PaddlePaddle using pip:

```bash
# for CUDA11.8
python -m pip install paddlepaddle-gpu==3.0.0 -i https://www.paddlepaddle.org.cn/packages/stable/cu118/

# for CUDA12.6
python -m pip install paddlepaddle-gpu==3.0.0 -i https://www.paddlepaddle.org.cn/packages/stable/cu126/

# for CPU
python -m pip install paddlepaddle==3.0.0 -i https://www.paddlepaddle.org.cn/packages/stable/cpu/
```

For details about PaddlePaddle installation, please refer to the [PaddlePaddle official website](https://www.paddlepaddle.org.cn/en/install/quick).

2. PaddleOCR

Install the latest version of the PaddleOCR inference package from PyPI:

```bash
python -m pip install paddleocr
```


### Model Usage

You can quickly experience the functionality with a single command:

```bash
paddleocr layout_detection \
    --model_name PP-DocLayout-S \
    -i https://cdn-uploads.huggingface.co/production/uploads/63d7b8ee07cd1aa3c49a2026/N5C68HPVAI-xQAWTxpbA6.jpeg
```

You can also integrate the model inference of the layout detection module into your project. Before running the following code, please download the sample image to your local machine.

```python
from paddleocr import LayoutDetection

model = LayoutDetection(model_name="PP-DocLayout-S")
output = model.predict("N5C68HPVAI-xQAWTxpbA6.jpeg", batch_size=1, layout_nms=True)
for res in output:
    res.print()
    res.save_to_img(save_path="./output/")
    res.save_to_json(save_path="./output/res.json")
```

After running, the obtained result is as follows:

```json
{'res': {'input_path': '/root/.paddlex/predict_input/N5C68HPVAI-xQAWTxpbA6.jpeg', 'page_index': None, 'boxes': [{'cls_id': 2, 'label': 'text', 'score': 0.9695586562156677, 'coordinate': [33.89038, 646.9188, 359.24533, 849.0932]}, {'cls_id': 2, 'label': 'text', 'score': 0.967388927936554, 'coordinate': [384.86646, 735.1845, 712.85364, 850.4103]}, {'cls_id': 2, 'label': 'text', 'score': 0.9618191123008728, 'coordinate': [386.01096, 495.6391, 712.27875, 695.3851]}, {'cls_id': 2, 'label': 'text', 'score': 0.961749792098999, 'coordinate': [32.8393, 349.90964, 360.12332, 612.03687]}, {'cls_id': 8, 'label': 'table', 'score': 0.9275020360946655, 'coordinate': [438.0261, 106.43789, 663.54443, 313.9396]}, {'cls_id': 8, 'label': 'table', 'score': 0.9272584319114685, 'coordinate': [74.408966, 107.16657, 319.52042, 300.95004]}, {'cls_id': 2, 'label': 'text', 'score': 0.7687780261039734, 'coordinate': [386.9146, 20.052937, 711.3798, 75.4542]}, {'cls_id': 2, 'label': 'text', 'score': 0.7498785853385925, 'coordinate': [34.74602, 19.907455, 359.0445, 76.057304]}, {'cls_id': 0, 'label': 'paragraph_title', 'score': 0.7419086694717407, 'coordinate': [386.08258, 715.97784, 525.2569, 729.5398]}, {'cls_id': 0, 'label': 'paragraph_title', 'score': 0.7388646602630615, 'coordinate': [35.2029, 331.77637, 141.53833, 344.5923]}, {'cls_id': 0, 'label': 'paragraph_title', 'score': 0.6813259124755859, 'coordinate': [37.83614, 629.0971, 185.95276, 640.6417]}, {'cls_id': 2, 'label': 'text', 'score': 0.6515917778015137, 'coordinate': [385.31564, 346.86404, 712.0084, 460.2663]}, {'cls_id': 0, 'label': 'paragraph_title', 'score': 0.5848217606544495, 'coordinate': [384.1938, 477.32938, 706.8187, 491.30426]}]}}
```

The visualized image is as follows:

![image/jpeg](https://cdn-uploads.huggingface.co/production/uploads/63d7b8ee07cd1aa3c49a2026/uYUJouAHxLx0FC0vjsQpC.jpeg)

For details about usage command and descriptions of parameters, please refer to the [Document](https://paddlepaddle.github.io/PaddleOCR/latest/en/version3.x/module_usage/layout_detection.html#iii-quick-integration).

### Pipeline Usage

The ability of a single model is limited. But the pipeline consists of several models can provide more capacity to resolve difficult problems in real-world scenarios.

#### PP-StructureV3

Layout analysis is a technique used to extract structured information from document images. PP-StructureV3 includes the following six modules:
* Layout Detection Module
* General OCR Sub-pipeline
* Document Image Preprocessing Sub-pipeline （Optional）
* Table Recognition Sub-pipeline （Optional）
* Seal Recognition Sub-pipeline （Optional）
* Formula Recognition Sub-pipeline （Optional）

You can quickly experience the PP-StructureV3 pipeline with a single command.

```bash
paddleocr pp_structurev3 --layout_detection_model_name PP-DocLayout-S -i https://cdn-uploads.huggingface.co/production/uploads/63d7b8ee07cd1aa3c49a2026/KP10tiSZfAjMuwZUSLtRp.png
```

You can experience the inference of the pipeline with just a few lines of code. Taking the PP-StructureV3 pipeline as an example:

```python
from paddleocr import PPStructureV3

pipeline = PPStructureV3(layout_detection_model_name="PP-DocLayout-S")
# ocr = PPStructureV3(use_doc_orientation_classify=True) # Use use_doc_orientation_classify to enable/disable document orientation classification model
# ocr = PPStructureV3(use_doc_unwarping=True) # Use use_doc_unwarping to enable/disable document unwarping module
# ocr = PPStructureV3(use_textline_orientation=True) # Use use_textline_orientation to enable/disable textline orientation classification model
# ocr = PPStructureV3(device="gpu") # Use device to specify GPU for model inference
output = pipeline.predict("./KP10tiSZfAjMuwZUSLtRp.png")
for res in output:
    res.print() ## Print the structured prediction output
    res.save_to_json(save_path="output") ## Save the current image's structured result in JSON format
    res.save_to_markdown(save_path="output") ## Save the current image's result in Markdown format
```

The default model used in pipeline is `PP-DocLayout_plus-L`, so it is needed that specifing to `PP-DocLayout-S` by argument `layout_detection_model_name`. And you can also use the local model file by argument `layout_detection_model_dir`. 
For details about usage command and descriptions of parameters, please refer to the [Document](https://paddlepaddle.github.io/PaddleOCR/latest/en/version3.x/pipeline_usage/PP-StructureV3.html#2-quick-start).

## Links

[PaddleOCR Repo](https://github.com/paddlepaddle/paddleocr)

[PaddleOCR Documentation](https://paddlepaddle.github.io/PaddleOCR/latest/en/index.html)

