#!/usr/bin/env python3
"""Generate synthetic multilingual dataset for Free Cursor model training."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import math
import random
import re
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

TOTAL_CLEANED = 60000
TRAIN_COUNT = 48000
VAL_COUNT = 6000
TEST_COUNT = 6000

LANGUAGE_COUNTS = {
    "egy_ar": 18000,
    "msa_ar": 12000,
    "en": 12000,
    "es": 3600,
    "fr": 3600,
    "hi": 3600,
    "tr": 3600,
    "ur": 3600,
}

ACTION_COUNTS = {
    "click": 14400,
    "type": 7800,
    "scroll": 6000,
    "swipe": 4200,
    "long_press": 3000,
    "launch_app": 9000,
    "back": 3000,
    "home": 2400,
    "recent_apps": 1800,
    "open_notifications": 1200,
    "open_quick_settings": 600,
    "noop": 6600,
}

ALLOWED_ACTIONS = list(ACTION_COUNTS.keys())

UI_CURSOR_ACTIONS = {"click", "type", "scroll", "swipe", "long_press"}
SYSTEM_DIRECT_ACTIONS = {
    "launch_app",
    "back",
    "home",
    "recent_apps",
    "open_notifications",
    "open_quick_settings",
}

APP_MAPPING = [
    {"app_name": "WhatsApp", "package_name": "com.whatsapp"},
    {"app_name": "Phone", "package_name": "com.google.android.dialer"},
    {"app_name": "Messages", "package_name": "com.google.android.apps.messaging"},
    {"app_name": "Chrome", "package_name": "com.android.chrome"},
    {"app_name": "Settings", "package_name": "com.android.settings"},
    {"app_name": "Camera", "package_name": "com.google.android.GoogleCamera"},
    {"app_name": "YouTube", "package_name": "com.google.android.youtube"},
    {"app_name": "Gmail", "package_name": "com.google.android.gm"},
    {"app_name": "Maps", "package_name": "com.google.android.apps.maps"},
    {"app_name": "Play Store", "package_name": "com.android.vending"},
]

PACKAGE_POOL = [
    "com.whatsapp",
    "com.android.chrome",
    "com.google.android.youtube",
    "com.google.android.gm",
    "com.google.android.apps.maps",
    "com.android.settings",
    "com.google.android.apps.messaging",
    "com.google.android.dialer",
    "com.android.vending",
    "com.google.android.GoogleCamera",
    "com.microsoft.office.outlook",
    "com.spotify.music",
    "org.telegram.messenger",
]

SCREEN_PRESETS = [
    (1080, 2400, 2.75),
    (1080, 2340, 2.75),
    (720, 1600, 2.50),
    (1440, 3120, 3.50),
    (1080, 1920, 2.50),
]

ROLES = ["button", "text", "textfield", "checkbox", "switch", "image", "list", "view"]
BUTTON_TEXTS = [
    "Search",
    "Send",
    "Open",
    "Next",
    "Save",
    "Done",
    "Allow",
    "Skip",
    "Continue",
    "Apply",
]
TEXT_HINTS = [
    "Search",
    "Type message",
    "Enter email",
    "Enter password",
    "Find contact",
    "Write comment",
]

COMMAND_TEMPLATES = {
    "egy_ar": {
        "click": [
            "دوس على {target}",
            "اضغط بسرعة على {target}",
            "افتح {target}",
            "اختار {target}",
        ],
        "type": [
            "اكتب \"{text}\" في الخانة",
            "حط النص ده \"{text}\"",
            "دخل \"{text}\" في الحقل",
        ],
        "scroll": [
            "مرر لتحت شوية",
            "سكرول لفوق",
            "انزل في الصفحة",
        ],
        "swipe": [
            "اسحب لليسار",
            "اسحب لفوق",
            "سحب سريع ناحية اليمين",
        ],
        "long_press": [
            "دوس ضغطة مطولة على {target}",
            "اضغط مطول على {target}",
        ],
        "launch_app": [
            "افتح {app}",
            "شغل تطبيق {app}",
            "روح على {app}",
        ],
        "back": ["ارجع", "رجوع", "ارجع للشاشة اللي قبلها"],
        "home": ["روح للرئيسية", "افتح الهوم", "الشاشة الرئيسية"],
        "recent_apps": ["افتح التطبيقات الأخيرة", "وريني آخر التطبيقات"],
        "open_notifications": ["افتح الإشعارات", "نزل شريط الإشعارات"],
        "open_quick_settings": ["افتح الإعدادات السريعة", "افتح quick settings"],
        "noop": [
            "اعمل حاجة مناسبة",
            "نفذ كده وخلاص",
            "صلح الموضوع بسرعة",
            "مش عارف بس خلصها",
        ],
    },
    "msa_ar": {
        "click": ["اضغط على {target}", "انقر على {target}", "افتح {target}"],
        "type": ["اكتب \"{text}\" في الحقل", "أدخل النص \"{text}\""],
        "scroll": ["مرر إلى الأسفل", "مرر إلى الأعلى"],
        "swipe": ["اسحب إلى اليسار", "اسحب إلى الأعلى"],
        "long_press": ["اضغط مطولًا على {target}"],
        "launch_app": ["افتح تطبيق {app}", "شغّل {app}"],
        "back": ["عودة", "ارجع خطوة للخلف"],
        "home": ["اذهب إلى الشاشة الرئيسية"],
        "recent_apps": ["افتح التطبيقات الأخيرة"],
        "open_notifications": ["افتح لوحة الإشعارات"],
        "open_quick_settings": ["افتح الإعدادات السريعة"],
        "noop": ["نفّذ الإجراء المناسب", "أصلح الوضع الحالي", "تابع التنفيذ"],
    },
    "en": {
        "click": ["tap {target}", "click {target}", "open {target}"],
        "type": ["type \"{text}\"", "enter \"{text}\" in the field"],
        "scroll": ["scroll down", "scroll up"],
        "swipe": ["swipe left", "swipe up"],
        "long_press": ["long press {target}"],
        "launch_app": ["open {app}", "launch {app} app"],
        "back": ["go back", "back"],
        "home": ["go to home", "open home screen"],
        "recent_apps": ["open recent apps"],
        "open_notifications": ["open notifications"],
        "open_quick_settings": ["open quick settings"],
        "noop": ["do the right thing", "handle this", "fix this now"],
    },
    "es": {
        "click": ["toca {target}", "haz clic en {target}"],
        "type": ["escribe \"{text}\"", "ingresa \"{text}\""],
        "scroll": ["desplaza hacia abajo", "desplaza hacia arriba"],
        "swipe": ["desliza a la izquierda", "desliza hacia arriba"],
        "long_press": ["mantén pulsado {target}"],
        "launch_app": ["abre {app}", "inicia {app}"],
        "back": ["volver", "regresa"],
        "home": ["ir a inicio"],
        "recent_apps": ["abre apps recientes"],
        "open_notifications": ["abre notificaciones"],
        "open_quick_settings": ["abre ajustes rápidos"],
        "noop": ["haz algo útil", "resuelve esto"],
    },
    "fr": {
        "click": ["appuie sur {target}", "ouvre {target}"],
        "type": ["écris \"{text}\"", "saisis \"{text}\""],
        "scroll": ["fais défiler vers le bas", "fais défiler vers le haut"],
        "swipe": ["glisse à gauche", "glisse vers le haut"],
        "long_press": ["appui long sur {target}"],
        "launch_app": ["ouvre {app}", "lance {app}"],
        "back": ["retour", "reviens en arrière"],
        "home": ["aller à l'accueil"],
        "recent_apps": ["ouvre les applis récentes"],
        "open_notifications": ["ouvre les notifications"],
        "open_quick_settings": ["ouvre les réglages rapides"],
        "noop": ["fais le nécessaire", "corrige cela"],
    },
    "hi": {
        "click": ["{target} पर टैप करो", "{target} खोलो"],
        "type": ["\"{text}\" टाइप करो", "\"{text}\" दर्ज करो"],
        "scroll": ["नीचे स्क्रॉल करो", "ऊपर स्क्रॉल करो"],
        "swipe": ["बाएं स्वाइप करो", "ऊपर स्वाइप करो"],
        "long_press": ["{target} को लंबा दबाओ"],
        "launch_app": ["{app} खोलो", "{app} ऐप चालू करो"],
        "back": ["पीछे जाओ", "बैक"],
        "home": ["होम स्क्रीन पर जाओ"],
        "recent_apps": ["हाल की ऐप्स खोलो"],
        "open_notifications": ["नोटिफिकेशन खोलो"],
        "open_quick_settings": ["क्विक सेटिंग्स खोलो"],
        "noop": ["कुछ सही करो", "इसे ठीक करो"],
    },
    "tr": {
        "click": ["{target} öğesine dokun", "{target} aç"],
        "type": ["\"{text}\" yaz", "\"{text}\" gir"],
        "scroll": ["aşağı kaydır", "yukarı kaydır"],
        "swipe": ["sola kaydır", "yukarı kaydır"],
        "long_press": ["{target} üzerinde uzun bas"],
        "launch_app": ["{app} aç", "{app} uygulamasını başlat"],
        "back": ["geri git", "geri"],
        "home": ["ana ekrana git"],
        "recent_apps": ["son uygulamaları aç"],
        "open_notifications": ["bildirimleri aç"],
        "open_quick_settings": ["hızlı ayarları aç"],
        "noop": ["uygun işlemi yap", "bunu düzelt"],
    },
    "ur": {
        "click": ["{target} پر ٹیپ کریں", "{target} کھولیں"],
        "type": ["\"{text}\" لکھیں", "\"{text}\" درج کریں"],
        "scroll": ["نیچے اسکرول کریں", "اوپر اسکرول کریں"],
        "swipe": ["بائیں سوائپ کریں", "اوپر سوائپ کریں"],
        "long_press": ["{target} پر لمبا دبائیں"],
        "launch_app": ["{app} کھولیں", "{app} ایپ چلائیں"],
        "back": ["واپس جائیں", "بیک"],
        "home": ["ہوم اسکرین پر جائیں"],
        "recent_apps": ["حالیہ ایپس کھولیں"],
        "open_notifications": ["نوٹیفکیشنز کھولیں"],
        "open_quick_settings": ["فوری ترتیبات کھولیں"],
        "noop": ["مناسب کام کریں", "اسے درست کریں"],
    },
}

PII_EMAIL_RE = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
PII_PHONE_RE = re.compile(r"(?<!\\w)(?:\\+?\\d[\\d\\-\\s]{7,}\\d)(?!\\w)")
PII_OTP_RE = re.compile(r"\\b\\d{4,8}\\b")
PII_TOKEN_RE = re.compile(r"([?&](?:token|auth|key|otp|code)=)[^&\\s]+", re.IGNORECASE)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Generate synthetic dataset for Free Cursor")
    parser.add_argument("--output", default="dataset", help="Output dataset directory")
    parser.add_argument("--seed", type=int, default=20260418, help="Random seed")
    return parser.parse_args()


def normalize_text(text: str) -> str:
    return re.sub(r"\\s+", " ", text.strip().lower())


def redact_text(text: str) -> tuple[str, list[str]]:
    flags: list[str] = []
    redacted = text

    if PII_EMAIL_RE.search(redacted):
        flags.append("email")
        redacted = PII_EMAIL_RE.sub("<REDACTED_EMAIL>", redacted)

    if PII_PHONE_RE.search(redacted):
        flags.append("phone")
        redacted = PII_PHONE_RE.sub("<REDACTED_PHONE>", redacted)

    if PII_TOKEN_RE.search(redacted):
        flags.append("token")
        redacted = PII_TOKEN_RE.sub(r"\\1<REDACTED_TOKEN>", redacted)

    if PII_OTP_RE.search(redacted):
        flags.append("otp")
        redacted = PII_OTP_RE.sub("<REDACTED_OTP>", redacted)

    return redacted, sorted(set(flags))


def build_joint_matrix(
    language_counts: dict[str, int],
    action_counts: dict[str, int],
) -> dict[str, dict[str, int]]:
    total = sum(language_counts.values())
    languages = list(language_counts.keys())
    actions = list(action_counts.keys())

    matrix = {lang: {action: 0 for action in actions} for lang in languages}
    fractions: list[tuple[float, str, str]] = []

    for lang in languages:
        for action in actions:
            expected = language_counts[lang] * action_counts[action] / total
            base = math.floor(expected)
            matrix[lang][action] = base
            fractions.append((expected - base, lang, action))

    row_remaining = {
        lang: language_counts[lang] - sum(matrix[lang].values())
        for lang in languages
    }
    col_remaining = {
        action: action_counts[action] - sum(matrix[lang][action] for lang in languages)
        for action in actions
    }

    fractions.sort(key=lambda item: item[0], reverse=True)

    for _, lang, action in fractions:
        if row_remaining[lang] > 0 and col_remaining[action] > 0:
            matrix[lang][action] += 1
            row_remaining[lang] -= 1
            col_remaining[action] -= 1

    while any(v > 0 for v in row_remaining.values()):
        for lang in languages:
            if row_remaining[lang] <= 0:
                continue
            for action in actions:
                if col_remaining[action] > 0:
                    matrix[lang][action] += 1
                    row_remaining[lang] -= 1
                    col_remaining[action] -= 1
                    if row_remaining[lang] == 0:
                        break

    if any(v != 0 for v in row_remaining.values()) or any(v != 0 for v in col_remaining.values()):
        raise RuntimeError("Failed to build exact joint matrix.")

    return matrix


def random_context(rng: random.Random) -> tuple[dict[str, Any], int, int, float]:
    width, height, density = rng.choice(SCREEN_PRESETS)
    orientation = "portrait"
    if rng.random() < 0.15:
        width, height = height, width
        orientation = "landscape"

    context = {
        "package_name": rng.choice(PACKAGE_POOL),
        "screen_size": f"{width}x{height}",
        "orientation": orientation,
        "screen_w": width,
        "screen_h": height,
        "density": density,
    }
    return context, width, height, density


def make_node(
    node_id: int,
    width: int,
    height: int,
    role: str,
    text: str,
    hint: str,
    rng: random.Random,
) -> dict[str, Any]:
    left = rng.randint(0, max(0, width - 240))
    top = rng.randint(0, max(0, height - 220))
    right = min(width, left + rng.randint(120, 420))
    bottom = min(height, top + rng.randint(64, 180))

    editable = role == "textfield"
    clickable = role in {"button", "checkbox", "switch", "image", "list", "view"} or editable

    actions = []
    if clickable:
        actions.append("click")
    if role in {"list", "view"}:
        actions.extend(["scroll_forward", "scroll_backward"])
    if role == "textfield":
        actions.append("set_text")
    if clickable and rng.random() < 0.2:
        actions.append("long_press")

    return {
        "id": node_id,
        "role": role,
        "text": text,
        "hint": hint,
        "clickable": clickable,
        "enabled": True if rng.random() > 0.02 else False,
        "editable": editable,
        "bounds": {
            "l": left,
            "t": top,
            "r": right,
            "b": bottom,
        },
        "packageName": rng.choice(PACKAGE_POOL),
        "className": f"android.widget.{role.title()}",
        "actions": sorted(set(actions)),
    }


def seed_text_for_type(rng: random.Random) -> str:
    options = [
        "hello from free cursor",
        "meeting at 5 pm",
        "check this later",
        "my email is test.user@example.com",
        "call me at +1 555 123 4567",
        "verification code 483920",
        "quick update done",
    ]
    return rng.choice(options)


def choose_target_label(nodes: list[dict[str, Any]], rng: random.Random) -> str:
    candidates = [n for n in nodes if n["text"]]
    if not candidates:
        return rng.choice(BUTTON_TEXTS)
    return rng.choice(candidates)["text"]


def build_screen_nodes(action: str, rng: random.Random, width: int, height: int) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    node_count = rng.randint(10, 28)
    nodes: list[dict[str, Any]] = []

    for node_id in range(1, node_count + 1):
        role = rng.choice(ROLES)
        text = ""
        hint = ""
        if role == "button":
            text = rng.choice(BUTTON_TEXTS)
        elif role == "textfield":
            hint = rng.choice(TEXT_HINTS)
        elif role == "text":
            text = rng.choice(["Home", "Chats", "Profile", "Search", "Settings", "Notifications"])

        nodes.append(make_node(node_id, width, height, role, text, hint, rng))

    target: dict[str, Any] = {
        "target_id": None,
        "start_id": None,
        "end_id": None,
        "text": None,
        "direction": None,
    }

    if action == "click":
        target_node = rng.choice(nodes)
        target_node["role"] = "button"
        target_node["text"] = rng.choice(BUTTON_TEXTS)
        target_node["clickable"] = True
        target_node["enabled"] = True
        target_node["actions"] = sorted(set(target_node["actions"] + ["click"]))
        target["target_id"] = target_node["id"]

    elif action == "type":
        editable_nodes = [node for node in nodes if node["role"] == "textfield"]
        if not editable_nodes:
            candidate = rng.choice(nodes)
            candidate["role"] = "textfield"
            candidate["editable"] = True
            candidate["clickable"] = True
            candidate["hint"] = rng.choice(TEXT_HINTS)
            candidate["actions"] = sorted(set(candidate["actions"] + ["set_text", "click"]))
            editable_nodes = [candidate]

        target_node = rng.choice(editable_nodes)
        target_node["editable"] = True
        target_node["enabled"] = True
        target_node["actions"] = sorted(set(target_node["actions"] + ["set_text", "click"]))
        target["target_id"] = target_node["id"]
        target["text"] = seed_text_for_type(rng)

    elif action == "scroll":
        list_nodes = [node for node in nodes if node["role"] in {"list", "view"}]
        if not list_nodes:
            candidate = rng.choice(nodes)
            candidate["role"] = "list"
            candidate["actions"] = sorted(set(candidate["actions"] + ["scroll_forward", "scroll_backward"]))
            list_nodes = [candidate]

        target_node = rng.choice(list_nodes)
        target["target_id"] = target_node["id"]
        target["direction"] = rng.choice(["up", "down"])

    elif action == "swipe":
        start = rng.choice(nodes)
        end = rng.choice(nodes)
        while end["id"] == start["id"]:
            end = rng.choice(nodes)
        target["start_id"] = start["id"]
        target["end_id"] = end["id"]
        target["target_id"] = start["id"]
        target["direction"] = rng.choice(["left", "right", "up", "down"])

    elif action == "long_press":
        target_node = rng.choice(nodes)
        target_node["clickable"] = True
        target_node["enabled"] = True
        target_node["actions"] = sorted(set(target_node["actions"] + ["long_press", "click"]))
        target["target_id"] = target_node["id"]

    return nodes, target


def build_user_command(
    language: str,
    action: str,
    rng: random.Random,
    target_label: str,
    app_name: str | None,
    text_payload: str | None,
) -> str:
    template = rng.choice(COMMAND_TEMPLATES[language][action])
    return template.format(
        target=target_label,
        app=app_name or "the app",
        text=text_payload or "text",
    )


def screen_signature(nodes: list[dict[str, Any]]) -> str:
    compact = [
        (
            node["role"],
            normalize_text(node.get("text", "")),
            node["bounds"]["l"] // 20,
            node["bounds"]["t"] // 20,
            node["bounds"]["r"] // 20,
            node["bounds"]["b"] // 20,
        )
        for node in nodes
    ]
    payload = json.dumps(compact, ensure_ascii=False, separators=(",", ":"))
    return hashlib.sha1(payload.encode("utf-8")).hexdigest()[:16]


def build_quality_tags(
    action: str,
    execution_mode: str,
    rng: random.Random,
) -> list[str]:
    tags: set[str] = set()

    if execution_mode == "system_direct":
        tags.add("shortcut_intent")
    if action == "noop":
        tags.add("ambiguous")
        if rng.random() < 0.5:
            tags.add("hard_negative")
    if action in {"scroll", "swipe"} and rng.random() < 0.35:
        tags.add("multistep")
    if action in {"click", "type"} and rng.random() < 0.12:
        tags.add("hard_negative")

    return sorted(tags)


def generate_valid_record(
    sample_idx: int,
    language: str,
    action: str,
    matrix_counter: int,
    rng: random.Random,
) -> dict[str, Any]:
    context, width, height, density = random_context(rng)
    nodes, node_target = build_screen_nodes(action, rng, width, height)

    app = rng.choice(APP_MAPPING)
    timestamp_start = datetime.now(timezone.utc).isoformat()
    timestamp_end = datetime.now(timezone.utc).isoformat()

    if action in UI_CURSOR_ACTIONS:
        execution_mode = "ui_cursor"
        requires_cursor = True
    elif action in SYSTEM_DIRECT_ACTIONS:
        execution_mode = "system_direct"
        requires_cursor = False
    else:
        # noop mixes both modes for richer stratification
        if rng.random() < 0.4:
            execution_mode = "system_direct"
            requires_cursor = False
        else:
            execution_mode = "ui_cursor"
            requires_cursor = True

    target_label = choose_target_label(nodes, rng)
    app_name = app["app_name"] if action == "launch_app" else None

    if action == "launch_app":
        target = {
            "action": action,
            "target_id": None,
            "text": None,
            "direction": None,
            "start_id": None,
            "end_id": None,
            "app_name": app["app_name"],
            "package_name": app["package_name"],
            "requires_cursor": requires_cursor,
            "execution_mode": execution_mode,
            "confidence": round(rng.uniform(0.80, 0.99), 3),
            "reason": "Direct application launch intent",
        }
    elif action in SYSTEM_DIRECT_ACTIONS:
        target = {
            "action": action,
            "target_id": None,
            "text": None,
            "direction": None,
            "start_id": None,
            "end_id": None,
            "app_name": None,
            "package_name": None,
            "requires_cursor": requires_cursor,
            "execution_mode": execution_mode,
            "confidence": round(rng.uniform(0.75, 0.97), 3),
            "reason": "System shortcut intent",
        }
    elif action == "noop":
        target = {
            "action": action,
            "target_id": None,
            "text": None,
            "direction": None,
            "start_id": None,
            "end_id": None,
            "app_name": None,
            "package_name": None,
            "requires_cursor": requires_cursor,
            "execution_mode": execution_mode,
            "confidence": round(rng.uniform(0.35, 0.66), 3),
            "reason": rng.choice(
                [
                    "Instruction is ambiguous",
                    "Target not found in current hierarchy",
                    "Conflicting command constraints",
                ]
            ),
        }
    else:
        target = {
            "action": action,
            "target_id": node_target["target_id"],
            "text": node_target["text"],
            "direction": node_target["direction"],
            "start_id": node_target["start_id"],
            "end_id": node_target["end_id"],
            "app_name": None,
            "package_name": context["package_name"],
            "requires_cursor": requires_cursor,
            "execution_mode": execution_mode,
            "confidence": round(rng.uniform(0.72, 0.98), 3),
            "reason": "UI hierarchy target selection",
        }

    command = build_user_command(
        language=language,
        action=action,
        rng=rng,
        target_label=target_label,
        app_name=app_name,
        text_payload=target.get("text"),
    )

    return {
        "sample_id": f"S{sample_idx:06d}",
        "session_id": f"session_{language}_{matrix_counter // 20:05d}",
        "timestamp_start": timestamp_start,
        "timestamp_end": timestamp_end,
        "language": language,
        "user_command_raw": command,
        "user_command_normalized": normalize_text(command),
        "screen_nodes": nodes,
        "allowed_actions": ALLOWED_ACTIONS,
        "context": {
            "package_name": context["package_name"],
            "screen_size": context["screen_size"],
            "orientation": context["orientation"],
            "device": {
                "model": rng.choice(["Pixel 8", "Pixel 7", "Galaxy S23", "Xiaomi 13", "OnePlus 12"]),
                "api_level": rng.choice([29, 30, 31, 32, 33, 34, 35]),
                "screen_w": width,
                "screen_h": height,
                "density": density,
            },
        },
        "target": target,
        "quality_tags": build_quality_tags(action, execution_mode, rng),
        "source": "synthetic",
    }


def create_broken_record(base: dict[str, Any], idx: int, rng: random.Random) -> dict[str, Any]:
    broken = copy.deepcopy(base)
    broken["sample_id"] = f"B{idx:06d}"

    if idx % 2 == 0:
        broken["target"]["action"] = "click"
        broken["target"]["target_id"] = 999999
        broken["target"]["execution_mode"] = "ui_cursor"
        broken["target"]["requires_cursor"] = True
        broken["target"]["reason"] = "broken_target_id"
    else:
        broken["target"]["action"] = "launch_app"
        broken["target"]["app_name"] = rng.choice(["WhatsApp", "Chrome", "Maps"])
        broken["target"]["package_name"] = ""
        broken["target"]["execution_mode"] = "system_direct"
        broken["target"]["requires_cursor"] = False
        broken["target"]["reason"] = "missing_package_name"

    return broken


def validate_record(record: dict[str, Any]) -> str | None:
    target = record.get("target", {})
    action = target.get("action")
    if action not in ALLOWED_ACTIONS:
        return "unsupported_action"

    node_ids = {node.get("id") for node in record.get("screen_nodes", [])}

    if action in UI_CURSOR_ACTIONS:
        target_id = target.get("target_id")
        if target_id is None or target_id not in node_ids:
            return "invalid_target_id"
        if action == "swipe":
            if target.get("start_id") not in node_ids or target.get("end_id") not in node_ids:
                return "invalid_swipe_ids"
        if action == "type" and not target.get("text"):
            return "missing_type_text"

    if action == "launch_app":
        if not target.get("app_name") or not target.get("package_name"):
            return "missing_launch_app_fields"

    if set(record.get("allowed_actions", [])) != set(ALLOWED_ACTIONS):
        return "allowed_actions_mismatch"

    return None


def redact_record(record: dict[str, Any]) -> tuple[dict[str, Any], list[str]]:
    updated = copy.deepcopy(record)
    flags: set[str] = set()

    command_raw, command_flags = redact_text(updated["user_command_raw"])
    updated["user_command_raw"] = command_raw
    updated["user_command_normalized"] = normalize_text(command_raw)
    flags.update(command_flags)

    for node in updated.get("screen_nodes", []):
        text, f1 = redact_text(node.get("text", ""))
        hint, f2 = redact_text(node.get("hint", ""))
        node["text"] = text
        node["hint"] = hint
        flags.update(f1)
        flags.update(f2)

    if updated["target"].get("text"):
        text, tf = redact_text(str(updated["target"]["text"]))
        updated["target"]["text"] = text
        flags.update(tf)

    return updated, sorted(flags)


def dedup_key(record: dict[str, Any]) -> tuple[str, str, str, str]:
    return (
        record["language"],
        record["user_command_normalized"],
        screen_signature(record["screen_nodes"]),
        record["target"]["action"],
    )


def stratified_split(records: list[dict[str, Any]], seed: int) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]]]:
    groups: dict[tuple[str, str, str], list[dict[str, Any]]] = defaultdict(list)
    for rec in records:
        group_key = (rec["language"], rec["target"]["action"], rec["target"]["execution_mode"])
        groups[group_key].append(rec)

    rng = random.Random(seed)
    for items in groups.values():
        rng.shuffle(items)

    allocations: dict[tuple[str, str, str], dict[str, int]] = {}
    total_train = 0
    total_val = 0

    for group_key, items in groups.items():
        n = len(items)
        train_floor = math.floor(n * 0.8)
        val_floor = math.floor(n * 0.1)
        test_floor = n - train_floor - val_floor
        allocations[group_key] = {
            "train": train_floor,
            "val": val_floor,
            "test": test_floor,
            "frac_train": (n * 0.8) - train_floor,
            "frac_val": (n * 0.1) - val_floor,
        }
        total_train += train_floor
        total_val += val_floor

    need_train = TRAIN_COUNT - total_train
    need_val = VAL_COUNT - total_val

    train_order = sorted(
        allocations.keys(),
        key=lambda key: allocations[key]["frac_train"],
        reverse=True,
    )

    idx = 0
    while need_train > 0:
        key = train_order[idx % len(train_order)]
        if allocations[key]["test"] > 0:
            allocations[key]["train"] += 1
            allocations[key]["test"] -= 1
            need_train -= 1
        idx += 1

    val_order = sorted(
        allocations.keys(),
        key=lambda key: allocations[key]["frac_val"],
        reverse=True,
    )

    idx = 0
    while need_val > 0:
        key = val_order[idx % len(val_order)]
        if allocations[key]["test"] > 0:
            allocations[key]["val"] += 1
            allocations[key]["test"] -= 1
            need_val -= 1
        idx += 1

    train: list[dict[str, Any]] = []
    val: list[dict[str, Any]] = []
    test: list[dict[str, Any]] = []

    for key, items in groups.items():
        a = allocations[key]
        train.extend(items[: a["train"]])
        val.extend(items[a["train"] : a["train"] + a["val"]])
        test.extend(items[a["train"] + a["val"] :])

    if len(train) != TRAIN_COUNT or len(val) != VAL_COUNT or len(test) != TEST_COUNT:
        raise RuntimeError(
            f"Split counts mismatch: train={len(train)} val={len(val)} test={len(test)}",
        )

    return train, val, test


def write_jsonl(path: Path, records: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        for record in records:
            f.write(json.dumps(record, ensure_ascii=False))
            f.write("\n")


def counter_by(records: list[dict[str, Any]], field: str) -> dict[str, int]:
    ctr: Counter[str] = Counter()
    for rec in records:
        if field == "action":
            ctr[rec["target"]["action"]] += 1
        elif field == "language":
            ctr[rec["language"]] += 1
        elif field == "mode":
            ctr[rec["target"]["execution_mode"]] += 1
    return dict(sorted(ctr.items()))


def build_data_card(stats: dict[str, Any]) -> str:
    lines = [
        "# Free Cursor Synthetic Dataset",
        "",
        "## Summary",
        f"- Generated at: {stats['generated_at']}",
        f"- Raw samples: {stats['counts']['raw_total']}",
        f"- Cleaned samples: {stats['counts']['cleaned_total']}",
        f"- Rejected samples: {stats['counts']['rejected_total']}",
        "",
        "## Splits",
        f"- Train: {stats['counts']['train']}",
        f"- Val: {stats['counts']['val']}",
        f"- Test: {stats['counts']['test']}",
        "",
        "## Actions",
    ]
    for action, value in stats["distribution"]["cleaned"]["action"].items():
        lines.append(f"- {action}: {value}")

    lines.extend([
        "",
        "## Languages",
    ])
    for lang, value in stats["distribution"]["cleaned"]["language"].items():
        lines.append(f"- {lang}: {value}")

    lines.extend([
        "",
        "## Notes",
        "- Dataset is synthetic and template-driven.",
        "- PII redaction is applied before writing cleaned/split files.",
        "- System-direct actions are included to support non-cursor shortcuts.",
    ])

    return "\n".join(lines) + "\n"


def main() -> None:
    args = parse_args()
    rng = random.Random(args.seed)

    output_root = Path(args.output)
    raw_dir = output_root / "raw"
    processed_dir = output_root / "processed"
    splits_dir = output_root / "splits"
    meta_dir = output_root / "meta"

    for directory in (raw_dir, processed_dir, splits_dir, meta_dir):
        directory.mkdir(parents=True, exist_ok=True)

    matrix = build_joint_matrix(LANGUAGE_COUNTS, ACTION_COUNTS)

    valid_records: list[dict[str, Any]] = []
    sample_idx = 1

    for language, action_map in matrix.items():
        for action, count in action_map.items():
            for j in range(count):
                valid_records.append(
                    generate_valid_record(
                        sample_idx=sample_idx,
                        language=language,
                        action=action,
                        matrix_counter=j,
                        rng=rng,
                    ),
                )
                sample_idx += 1

    if len(valid_records) != TOTAL_CLEANED:
        raise RuntimeError(f"Expected {TOTAL_CLEANED} valid records, got {len(valid_records)}")

    duplicates = [copy.deepcopy(rng.choice(valid_records)) for _ in range(800)]
    for i, record in enumerate(duplicates, start=1):
        record["sample_id"] = f"D{i:06d}"

    broken = [create_broken_record(rng.choice(valid_records), i, rng) for i in range(1, 1201)]

    raw_records = valid_records + duplicates + broken
    rng.shuffle(raw_records)

    write_jsonl(raw_dir / "synthetic_raw.jsonl", raw_records)

    cleaned: list[dict[str, Any]] = []
    rejected: list[dict[str, Any]] = []
    seen_keys: set[tuple[str, str, str, str]] = set()

    for record in raw_records:
        redacted, redaction_flags = redact_record(record)
        reason = validate_record(redacted)
        if reason:
            rejected.append(
                {
                    "sample_id": redacted.get("sample_id"),
                    "reason": reason,
                    "record": redacted,
                },
            )
            continue

        key = dedup_key(redacted)
        if key in seen_keys:
            rejected.append(
                {
                    "sample_id": redacted.get("sample_id"),
                    "reason": "duplicate",
                    "record": redacted,
                },
            )
            continue

        seen_keys.add(key)
        redacted["redaction_flags"] = redaction_flags
        cleaned.append(redacted)

    if len(cleaned) != TOTAL_CLEANED:
        raise RuntimeError(f"Expected {TOTAL_CLEANED} cleaned records, got {len(cleaned)}")

    write_jsonl(processed_dir / "cleaned.jsonl", cleaned)
    write_jsonl(processed_dir / "rejected.jsonl", rejected)

    train, val, test = stratified_split(cleaned, args.seed)
    write_jsonl(splits_dir / "train.jsonl", train)
    write_jsonl(splits_dir / "val.jsonl", val)
    write_jsonl(splits_dir / "test.jsonl", test)

    split_records = {
        "train": train,
        "val": val,
        "test": test,
    }

    rejection_reasons = Counter(item["reason"] for item in rejected)

    stats = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "seed": args.seed,
        "counts": {
            "raw_total": len(raw_records),
            "cleaned_total": len(cleaned),
            "rejected_total": len(rejected),
            "train": len(train),
            "val": len(val),
            "test": len(test),
        },
        "distribution": {
            "cleaned": {
                "language": counter_by(cleaned, "language"),
                "action": counter_by(cleaned, "action"),
                "mode": counter_by(cleaned, "mode"),
            },
            "splits": {
                name: {
                    "language": counter_by(records, "language"),
                    "action": counter_by(records, "action"),
                    "mode": counter_by(records, "mode"),
                }
                for name, records in split_records.items()
            },
        },
        "targets": {
            "language": LANGUAGE_COUNTS,
            "action": ACTION_COUNTS,
        },
        "rejected_reasons": dict(sorted(rejection_reasons.items())),
        "acceptance_checks": {
            "cleaned_size_ok": len(cleaned) == TOTAL_CLEANED,
            "split_size_ok": len(train) == TRAIN_COUNT and len(val) == VAL_COUNT and len(test) == TEST_COUNT,
            "launch_app_has_package": all(
                rec["target"]["package_name"]
                for rec in cleaned
                if rec["target"]["action"] == "launch_app"
            ),
            "schema_validation_passed": True,
        },
    }

    schema = {
        "schema_version": "1.0.0",
        "actions": ALLOWED_ACTIONS,
        "languages": list(LANGUAGE_COUNTS.keys()),
        "record_fields": [
            "sample_id",
            "session_id",
            "timestamp_start",
            "timestamp_end",
            "language",
            "user_command_raw",
            "user_command_normalized",
            "screen_nodes",
            "allowed_actions",
            "context",
            "target",
            "quality_tags",
            "source",
            "redaction_flags",
        ],
        "target_fields": [
            "action",
            "target_id",
            "text",
            "direction",
            "start_id",
            "end_id",
            "app_name",
            "package_name",
            "requires_cursor",
            "execution_mode",
            "confidence",
            "reason",
        ],
    }

    with (meta_dir / "schema_version.json").open("w", encoding="utf-8") as f:
        json.dump(schema, f, ensure_ascii=False, indent=2)

    with (meta_dir / "app_mapping.json").open("w", encoding="utf-8") as f:
        json.dump(APP_MAPPING, f, ensure_ascii=False, indent=2)

    with (meta_dir / "stats.json").open("w", encoding="utf-8") as f:
        json.dump(stats, f, ensure_ascii=False, indent=2)

    with (meta_dir / "data_card.md").open("w", encoding="utf-8") as f:
        f.write(build_data_card(stats))

    print(json.dumps(
        {
            "status": "ok",
            "raw": len(raw_records),
            "cleaned": len(cleaned),
            "rejected": len(rejected),
            "train": len(train),
            "val": len(val),
            "test": len(test),
            "output": str(output_root),
        },
        ensure_ascii=False,
    ))


if __name__ == "__main__":
    main()
