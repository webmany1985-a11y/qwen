#!/usr/bin/env python3
# Проверка словарей заправки (FuelGrammar.phrases по шагам диалога) на синтезированных записях — до выпуска
# (критерий 8 карточки android-team/tasks/2026-11-05-zapravka-golosom). Речь — espeak-ng (ru, три голоса),
# шум машины — бурый шум ffmpeg (тихий и громкий). Словари по шагам выгружает FuelCheck grammar <папка>; смысл
# распознанного оценивает FuelCheck eval <tsv> — тем же разбором фраз, что в приложении.
# Запуск: python3 fuel_grammar_check.py <модель vosk> <папка словарей> <рабочая папка>  → <рабочая папка>/results.tsv
import json, os, subprocess, sys, wave

from vosk import Model, KaldiRecognizer, SetLogLevel

model_dir, gram_dir, work = sys.argv[1], sys.argv[2], sys.argv[3]
os.makedirs(work, exist_ok=True)

COMMON = ['стоп заправка', 'отмена заправки', 'прекрати заправку', 'не надо заправку', 'хватит заправки',
          'продолжить заправку', 'повтори', 'другая заправка']
DECOYS = ['да', 'нет', 'давай', 'конечно', 'подожди', 'потом', 'сколько стоит', 'включи музыку', 'открой ворота',
          'платье', 'оплата', 'не надо', 'хорошо', 'алло',
          # не из словаря-ловушек: слова, которых словарь не знает
          'поплатишься', 'плата за проезд', 'включи радио', 'позвони жене', 'какая погода', 'сделай погромче', 'заплатка']
CASES = {
    'ASK_PUMP': ['три', 'колонка три', 'третья колонка', 'третью', 'на третьей', 'колонку номер семь', 'двадцать четыре',
                 'колонка двенадцать', 'пятая', 'восьмая колонка', 'один', 'колонка шестнадцать', 'девять', 'колонка два',
                 'четвёртая', 'двадцать одна'] + COMMON,
    'ASK_AMOUNT': ['двадцать', 'двадцать литров', 'на двадцать литров', 'сорок литров', 'тридцать пять', 'пятнадцать литров',
                   'на тридцать рублей', 'тридцать рублей', 'на сумму сто', 'сто двадцать рублей', 'пятьдесят рублей',
                   'десять', 'на сорок', 'двенадцать литров', 'семьдесят рублей', 'на сумму пятьдесят'] + COMMON,
    'WAIT_READY': ['готово', 'снял', 'пистолет снят', 'колонка пять'] + COMMON,
    'ASK_PAY': ['оплатить', 'оплату разрешаю', 'оплати', 'да оплати', 'да разрешаю', 'плати', 'да плати', 'заплати',
                'двадцать литров', 'на тридцать рублей'] + COMMON,
}
VOICES = [('ru', 150, 50), ('ru+m3', 170, 40), ('ru+f2', 140, 60)]
NOISE = [None, 0.05, 0.12]

SetLogLevel(-1)
model = Model(model_dir)
grammars = {st: open(os.path.join(gram_dir, st + '.json'), encoding='utf-8').read() for st in CASES}


def synth(text, voice, speed, pitch, noise, path):
    raw = path + '.raw.wav'
    subprocess.run(['espeak-ng', '-v', voice, '-s', str(speed), '-p', str(pitch), '-w', raw, text], check=True)
    if noise is None:
        subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-i', raw, '-ar', '16000', '-ac', '1', path], check=True)
    else:
        subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-i', raw, '-f', 'lavfi', '-i',
                        f'anoisesrc=color=brown:amplitude={noise}:sample_rate=16000',
                        '-filter_complex', '[0:a]aresample=16000,apad=pad_dur=0.5[s];[s][1:a]amix=inputs=2:duration=first:normalize=0',
                        '-ac', '1', '-ar', '16000', path], check=True)


PAY = {'оплатить', 'оплату разрешаю', 'оплати', 'да оплати', 'да разрешаю', 'плати', 'да плати', 'заплати'}


def recognize(path, grammar):
    """Текст лучшего варианта и запас над ближайшим вариантом, который не оплата (как в приложении)."""
    rec = KaldiRecognizer(model, 16000, grammar)
    rec.SetMaxAlternatives(3)
    wf = wave.open(path, 'rb')
    while True:
        d = wf.readframes(4000)
        if not d:
            break
        rec.AcceptWaveform(d)
    alts = json.loads(rec.FinalResult()).get('alternatives', [])
    if not alts:
        return '', 1e9
    top = alts[0]
    gap = 1e9
    for a in alts[1:]:
        if a['text'].replace('[unk]', '').strip() not in PAY:
            gap = top['confidence'] - a['confidence']
            break
    return top['text'].replace('[unk]', '').strip(), gap


out = open(os.path.join(work, 'results.tsv'), 'w', encoding='utf-8')
k = 0
for st, phrases in CASES.items():
    items = [(p, False) for p in phrases] + ([(d, True) for d in DECOYS] if st in ('ASK_PAY', 'ASK_PUMP') else [])
    for ph, decoy in items:
        for v, sp, pi in VOICES:
            for nz in NOISE:
                k += 1
                p = os.path.join(work, f'{k}.wav')
                synth(ph, v, sp, pi, nz, p)
                got, conf = recognize(p, grammars[st])
                out.write(f'{st}\t{"!" if decoy else ""}{ph}\t{got}\t({v}, шум {nz})\t{conf:.2f}\n')
out.close()
print('записей:', k)
