# -*- coding: utf-8 -*-
"""生成儿童桌面 App 的两条提示语音（Qwen3-TTS 克隆 + 后处理交给 ffmpeg）。"""
import os
import warnings

os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")
warnings.filterwarnings("ignore")

import numpy as np
import soundfile as sf
import torch
from qwen_tts import Qwen3TTSModel

ROOT = r"E:\AI\EnglishPodcast\qwen3-tts"
MODEL = os.path.join(ROOT, "_models", "Qwen3-TTS-12Hz-1.7B-Base")
REF = os.path.join(ROOT, "_ref", "ref_female.wav")
OUT = r"D:\ProgramData\WorkBuddy\2026-10-21-13-41-57\voice"
os.makedirs(OUT, exist_ok=True)

LINES = [
    ("voice_rest", "小朋友，我累了，休息一下吧。"),
    ("voice_day_over", "小朋友，今天看得够多啦，明天再来找我玩。"),
]


def main():
    print("loading model ...", flush=True)
    model = Qwen3TTSModel.from_pretrained(
        MODEL,
        device_map="cuda:0",
        dtype=torch.bfloat16,
        attn_implementation="sdpa",
    )
    prompt = model.create_voice_clone_prompt(ref_audio=REF, x_vector_only_mode=True)
    print("generating ...", flush=True)

    for name, text in LINES:
        torch.manual_seed(1234)
        wavs, sr = model.generate_voice_clone(
            text=[text],
            language=["Chinese"],
            voice_clone_prompt=prompt,
        )
        wav = np.asarray(wavs[0], dtype=np.float32)
        path = os.path.join(OUT, name + "_raw.wav")
        sf.write(path, wav, sr)
        print("saved %s  sr=%s  dur=%.2fs" % (path, sr, len(wav) / float(sr)), flush=True)


if __name__ == "__main__":
    main()
