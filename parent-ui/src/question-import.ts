// 外部题库的字段名不统一（question / 题目 / stem、options / 选项、answer / 答案…），
// 而服务端只认 q / a / k / correctIndices 这一套。
// 这里在提交前统一转换，并按题号报告哪几道跳过、为什么跳过。
import {
  ANSWER_KEYS,
  AUDIO_KEYS,
  BANK_KEYS,
  IMAGE_KEYS,
  IMAGES_KEYS,
  LANGUAGE_KEYS,
  LEVEL_KEYS,
  LISTEN_KEYS,
  OWN_ANSWER_KEYS,
  SUBJECT_KEYS,
  TIP_KEYS,
  answerTexts,
  boolOf,
  clean,
  cleanMultiline,
  detectNumbering,
  difficultyOf,
  languageOf,
  lookup,
  optionsAndImagesOf,
  parseSelection,
  stemOf,
  typeOf,
} from './question-import-parsers.ts';

export type ImportIssue = { index: number; reason: string };
export type BankImport = { items: any[]; issues: ImportIssue[]; total: number };

function normalizeItem(item: any, numbering: 'zero' | 'one'): any {
  if (!item || typeof item !== 'object' || Array.isArray(item)) throw new Error('不是一条题目对象');
  const stem = stemOf(item);
  if (!stem) throw new Error('缺少题干（可用字段：q / question / 题目 / 题干）');
  if (stem.length > 5000) throw new Error('题干超过 5000 字');
  const { options, optionImages } = optionsAndImagesOf(item);
  const ownAnswer = lookup(item, OWN_ANSWER_KEYS);
  const answer = ownAnswer !== undefined ? ownAnswer : lookup(item, ANSWER_KEYS);
  const answerNumbering = ownAnswer !== undefined ? 'zero' : numbering;
  const type = typeOf(item, options, answer, answerNumbering);
  const out: any = { q: stem, type, difficulty: difficultyOf(item) };
  // 导出再导入时保留 id，重复导入同一份题库是覆盖而不是又加一份
  const id = clean(lookup(item, ['id']));
  if (id) out.id = id;

  if (type === 'boolean') {
    // 本项目用 k 表示选项序号：0 = 正确，1 = 错误；外部题库多用「正确 / 对 / √」
    const correct = typeof answer === 'number' ? answer === 0 : boolOf(answer);
    if (correct === null) throw new Error('判断题缺少正确答案（正确 / 错误）');
    out.a = ['正确', '错误'];
    out.k = correct ? 0 : 1;
  } else if (type === 'text') {
    const answers = answerTexts(answer);
    if (!answers.length) throw new Error('填空题缺少答案');
    out.answers = answers;
  } else if (type === 'subjective') {
    const model = cleanMultiline(lookup(item, ['modelAnswer', 'referenceAnswer', '参考答案', '标准答案', '答案']) ?? answer);
    out.modelAnswer = model;
    const rubric = cleanMultiline(lookup(item, ['rubric', '评分标准', '评分规则']));
    if (rubric) out.rubric = rubric;
  } else {
    if (options.length < 2) throw new Error('选择题至少需要 2 个选项');
    if (options.length > 8) throw new Error(`选项有 ${options.length} 个，最多支持 8 个`);
    if (options.some((o, i) => !o && !optionImages[i])) throw new Error('选项文字和配图不能同时为空');
    out.a = options;
    if (optionImages.length) out.optionImages = optionImages;
    const indices = parseSelection(answer, options, answerNumbering);
    if (!indices.length) throw new Error('缺少正确答案，或答案与选项对不上（可用字段：k / correctIndices / answer / 答案）');
    if (type === 'multiple') out.correctIndices = indices.sort((a, b) => a - b);
    else out.k = indices[0];
  }

  const tip = cleanMultiline(lookup(item, TIP_KEYS));
  if (tip) out.tip = tip;
  const subject = clean(lookup(item, SUBJECT_KEYS));
  if (subject) out.subject = subject;
  const level = clean(lookup(item, LEVEL_KEYS));
  if (level) out.level = level;
  const bank = clean(lookup(item, BANK_KEYS));
  if (bank) out.bank = bank.slice(0, 64);
  const rawImages = lookup(item, IMAGES_KEYS);
  const images = Array.isArray(rawImages) ? rawImages.map(clean).filter(Boolean).slice(0, 4) : [];
  const image = clean(lookup(item, IMAGE_KEYS)) || images[0] || '';
  if (image) out.image = image;
  if (images.length) out.images = images;
  const audio = clean(lookup(item, AUDIO_KEYS));
  if (audio) out.audio = audio;
  const listen = clean(lookup(item, LISTEN_KEYS)).slice(0, 1000);
  if (listen) out.listen = listen;
  // 听力文本优先：设备端不接受同时带音频地址，导入时先丢掉多余的 audio
  if (listen) delete (out as any).audio;
  const listenTimes = Number(lookup(item, ['listenTimes', '播放次数']));
  if (listen && listenTimes >= 1 && listenTimes <= 5) out.listenTimes = Math.floor(listenTimes);
  const listenRequired = lookup(item, ['listenRequired', '必须播放']);
  if (listen && typeof listenRequired === 'boolean') out.listenRequired = listenRequired;
  const listenRate = Number(lookup(item, ['listenRate', '语速']));
  if (listen && listenRate >= 0.7 && listenRate <= 1.5) out.listenRate = listenRate;
  const language = lookup(item, LANGUAGE_KEYS);
  if (language !== undefined) out.language = languageOf(language);
  const rawEnabled = lookup(item, ['enabled', '启用']);
  if (rawEnabled === false) out.enabled = false;
  return out;
}

function unwrap(input: any): any[] {
  if (Array.isArray(input)) return input;
  if (!input || typeof input !== 'object') return [];
  // {"banks":[{"name":"…","questions":[…]}…]}：把题库名带到每道题上
  if (Array.isArray(input.banks)) {
    const out: any[] = [];
    for (const bank of input.banks) {
      if (!bank || typeof bank !== 'object') continue;
      const list = Array.isArray(bank.questions) ? bank.questions : Array.isArray(bank.items) ? bank.items : [];
      const name = clean(bank.name ?? bank.title ?? bank.bank ?? '');
      for (const question of list) {
        out.push(name && question && typeof question === 'object' && !question.bank ? { ...question, bank: name } : question);
      }
    }
    if (out.length) return out;
  }
  for (const key of ['items', 'questions', 'list', 'data', '题库', '题目']) {
    const value = input[key];
    if (Array.isArray(value)) return value;
    if (value && typeof value === 'object') {
      const nested = unwrap(value);
      if (nested.length) return nested;
    }
  }
  return [input];
}

export function parseBankJson(raw: string): any {
  const text = raw.replace(/^\uFEFF/, '').trim();
  try {
    return JSON.parse(text);
  } catch (error) {
    // 有些题库导出成一行一题的 JSONL
    const lines = text.split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
    if (lines.length > 1) {
      try {
        return lines.map((line) => JSON.parse(line));
      } catch {
        /* 落到下面的报错 */
      }
    }
    throw new Error(`JSON 格式有误：${(error as Error).message}`);
  }
}

export function normalizeBank(input: any): BankImport {
  const raw = unwrap(input);
  const numbering = detectNumbering(raw);
  const items: any[] = [];
  const issues: ImportIssue[] = [];
  raw.forEach((item, index) => {
    try {
      items.push(normalizeItem(item, numbering));
    } catch (error: any) {
      issues.push({ index: index + 1, reason: error?.message || '格式无法识别' });
    }
  });
  return { items, issues, total: raw.length };
}
