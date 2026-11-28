// 外部题库字段映射、文本清洗、选项提取与答案解析工具。

// 题型别名。key 已去掉空格、下划线、连字符并转小写。
export const QUESTION_TYPES: Record<string, string> = {
  single: 'single', singlechoice: 'single', radio: 'single', onechoice: 'single',
  '单选': 'single', '单选题': 'single', '单项选择': 'single', '单项选择题': 'single', '选择题': 'single',
  multiple: 'multiple', multichoice: 'multiple', multiplechoice: 'multiple', checkbox: 'multiple', multianswer: 'multiple',
  '多选': 'multiple', '多选题': 'multiple', '多项选择': 'multiple', '多项选择题': 'multiple', '不定项选择': 'multiple', '不定项选择题': 'multiple',
  boolean: 'boolean', judge: 'boolean', judgement: 'boolean', truefalse: 'boolean', tf: 'boolean',
  '判断': 'boolean', '判断题': 'boolean', '是非题': 'boolean', '对错题': 'boolean', '正误题': 'boolean',
  text: 'text', fill: 'text', blank: 'text', fillblank: 'text', fillintheblank: 'text',
  '填空': 'text', '填空题': 'text',
  subjective: 'subjective', essay: 'subjective', shortanswer: 'subjective', qa: 'subjective',
  '主观': 'subjective', '主观题': 'subjective', '问答': 'subjective', '问答题': 'subjective',
  '简答': 'subjective', '简答题': 'subjective', '解答题': 'subjective', '论述题': 'subjective', '应用题': 'subjective',
};

export const STEM_KEYS = ['q', 'stem', 'question', 'questionText', 'title', 'content', 'text', '题干', '题目', '问题', '题目内容', '题目文字'];
export const OPTION_KEYS = ['a', 'options', 'choices', 'opts', 'option', 'items', '选择项', '选项', '选项列表', '备选项', '答案选项'];
export const TIP_KEYS = ['tip', 'explanation', 'explain', 'analysis', 'answerAnalysis', '解析', '解答', '说明', '讲解', '题目解析'];
export const SUBJECT_KEYS = ['subject', '学科', '科目'];
export const LEVEL_KEYS = ['level', 'grade', '年级', '学段'];
export const BANK_KEYS = ['bank', 'bankName', '题库', '题库名称', '题库名'];
export const IMAGE_KEYS = ['image', 'imageUrl', 'img', '图片', '图片地址'];
export const IMAGES_KEYS = ['images', 'imageUrls', 'imgs', '配图', '图片列表'];
export const OPTION_IMAGES_KEYS = ['optionImages', 'option_images', '选项配图', '选项图片'];
export const AUDIO_KEYS = ['audio', 'audioUrl', '音频', '音频地址'];
// 听力文本：只交给朗读、不显示在屏幕上（与 audio 二选一）
export const LISTEN_KEYS = ['listen', 'listenText', 'listening', '听力', '听力文本', '朗读文本', '听力内容'];
export const LANGUAGE_KEYS = ['language', 'lang', '语言'];

// 本项目的答案字段（序号从 0 开始）；其余答案字段（answer / 答案…）的序号规则要看整份题库。
export const OWN_ANSWER_KEYS = ['correctIndices', 'correctIndex', 'correct_index', 'k'];
export const ANSWER_KEYS = ['answers', 'answer', 'correct', 'correctAnswer', 'correctAnswers', 'key', 'solution', '正确答案', '答案', '正确选项', '参考答案'];

const BLOCK_BREAK = /<br\b[^>]*>|<\/(?:p|div|li|tr|h[1-6])\b[^>]*>/gi;
const TAG = /<\/?(?:p|br|div|span|b|i|u|s|strong|em|sub|sup|li|ul|ol|table|tbody|tr|td|th|h[1-6])\b[^>]*>/gi;
const OPTION_LABEL = /^\s*(?:[（(]\s*[A-Za-zＡ-Ｚ]\s*[)）]|[A-Za-zＡ-Ｚ]\s*[.、．:：)）])\s*/;

export function lookup(item: any, keys: string[]): any {
  // 字段名大小写不敏感：Answer / ANSWER / answer 都算
  const lower: Record<string, string> = {};
  for (const key of Object.keys(item)) lower[key.toLowerCase()] = key;
  for (const key of keys) {
    const hit = lower[key.toLowerCase()];
    if (hit !== undefined && item[hit] !== undefined && item[hit] !== null) return item[hit];
  }
  return undefined;
}

export function clean(value: any): string {
  return String(value ?? '')
    .replace(TAG, ' ')
    .replace(/&nbsp;/gi, ' ')
    .replace(/&lt;/gi, '<')
    .replace(/&gt;/gi, '>')
    .replace(/&amp;/gi, '&')
    .replace(/\s+/g, ' ')
    .trim();
}

/** 保留题干、解析、参考答案与评分标准中的 \n 换行（兼容富文本列表与分步解答） */
export function cleanMultiline(value: any): string {
  return String(value ?? '')
    .replace(/\r\n?/g, '\n')
    .replace(BLOCK_BREAK, '\n')
    .replace(TAG, ' ')
    .replace(/&nbsp;/gi, ' ')
    .replace(/&lt;/gi, '<')
    .replace(/&gt;/gi, '>')
    .replace(/&amp;/gi, '&')
    .split('\n')
    .map((line) => line.replace(/[^\S\n]+/g, ' ').trim())
    .join('\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

export function textOf(value: any): string {
  if (value && typeof value === 'object') {
    return clean(value.text ?? value.content ?? value.label ?? value.value ?? value.option ?? value.title ?? value.name ?? '');
  }
  return clean(value);
}

export function stemOf(item: any): string {
  // 按 STEM_KEYS 的顺序取第一个非空字段：q / stem / question 比 title 更可能是题干
  return cleanMultiline(lookup(item, STEM_KEYS));
}

function optionImagesRawOf(item: any, rawList?: any[]): string[] {
  const explicit = lookup(item, OPTION_IMAGES_KEYS);
  if (Array.isArray(explicit)) return explicit.map(clean);
  if (Array.isArray(rawList) && rawList.some((o) => o && typeof o === 'object' && (o.image || o.img || o.imageUrl))) {
    return rawList.map((o) => (o && typeof o === 'object' ? clean(o.image ?? o.img ?? o.imageUrl ?? '') : ''));
  }
  return [];
}

function finishOptions(list: any[], rawOptImgs: string[] = []): { options: string[]; optionImages: string[] } {
  const options = list.map(textOf).map((option) => option.replace(OPTION_LABEL, ''));
  const optImgs = options.map((_, i) => clean(rawOptImgs[i] ?? ''));
  // 首尾「既无文字也无配图」的空选项丢掉；有对应配图的纯图片选项必须保留
  while (options.length && !options[0] && !optImgs[0]) { options.shift(); optImgs.shift(); }
  while (options.length && !options[options.length - 1] && !optImgs[options.length - 1]) { options.pop(); optImgs.pop(); }
  return { options, optionImages: optImgs.some(Boolean) ? optImgs : [] };
}

export function optionsAndImagesOf(item: any): { options: string[]; optionImages: string[] } {
  let raw = lookup(item, OPTION_KEYS);
  if (raw && typeof raw === 'object' && !Array.isArray(raw)) {
    // {"A":"北京","B":"上海"} 这种按字母顺序还原
    raw = Object.keys(raw).sort().map((key) => raw[key]);
  }
  if (Array.isArray(raw)) return finishOptions(raw, optionImagesRawOf(item, raw));
  // 有些题库把选项拆成 optionA / optionB / 选项A 几个字段
  const lettered: string[] = [];
  for (const letter of 'ABCDEFGH') {
    const value = lookup(item, [`option${letter}`, `option_${letter}`, `${letter}选项`, `选项${letter}`]);
    if (value === undefined || typeof value === 'object') break;
    lettered.push(value);
  }
  return lettered.length >= 2 ? finishOptions(lettered, optionImagesRawOf(item)) : { options: [], optionImages: [] };
}

function optionsOf(item: any): string[] {
  return optionsAndImagesOf(item).options;
}

function letterIndex(token: string): number {
  const s = token.trim();
  if (s.length !== 1) return -1;
  const code = s.toUpperCase().charCodeAt(0);
  if (code >= 0xff21 && code <= 0xff3a) return code - 0xff21; // 全角 Ａ-Ｚ
  if (code >= 65 && code <= 90) return code - 65;
  return -1;
}

export function boolOf(raw: any): boolean | null {
  if (typeof raw === 'boolean') return raw;
  if (Array.isArray(raw)) return raw.length === 1 ? boolOf(raw[0]) : null;
  const s = String(raw ?? '').trim().toLowerCase();
  if (['正确', '对', '是', '真', '√', '✓', 'v', 't', 'true', 'yes', 'y', '1'].includes(s)) return true;
  if (['错误', '错', '否', '假', '×', '✗', 'x', 'f', 'false', 'no', 'n', '0'].includes(s)) return false;
  return null;
}

// 判断题以外的题型里，"1"/"0" 更可能是序号而不是对错
function boolWord(raw: any): boolean | null {
  if (typeof raw === 'boolean') return raw;
  const s = String(raw ?? '').trim().toLowerCase();
  if (['1', '0'].includes(s)) return null;
  return boolOf(s);
}

export function detectNumbering(items: any[]): 'zero' | 'one' {
  let sawZero = false;
  let sawMax = false;
  for (const item of items) {
    if (!item || typeof item !== 'object') continue;
    if (lookup(item, OWN_ANSWER_KEYS) !== undefined) continue; // 本项目字段一律 0 起
    const raw = lookup(item, ANSWER_KEYS);
    const length = optionsOf(item).length;
    if (!length) continue;
    for (const token of Array.isArray(raw) ? raw : [raw]) {
      const n = typeof token === 'number' ? token : /^\d+$/.test(String(token ?? '').trim()) ? Number(token) : NaN;
      if (!Number.isFinite(n)) continue;
      if (n === 0) sawZero = true;
      if (n === length) sawMax = true; // 等于选项个数只可能是从 1 开始数
    }
  }
  return sawMax && !sawZero ? 'one' : 'zero';
}

export function parseSelection(raw: any, options: string[], numbering: 'zero' | 'one'): number[] {
  let tokens: any[];
  if (Array.isArray(raw)) tokens = raw;
  else if (typeof raw === 'number') tokens = [raw];
  else if (typeof raw === 'string') {
    const s = raw.trim();
    if (!s) return [];
    const plain = s.replace(/[（）()【】\s]/g, '');
    // "ABD" / "ac" 这种连写的字母
    if (/^[A-Za-zＡ-Ｚａ-ｚ]+$/.test(plain) && plain.length > 1) tokens = plain.split('');
    else if (/^[A-Za-zＡ-Ｚａ-ｚ]$/.test(plain)) tokens = [plain];
    else tokens = plain.split(/[,，、;；/|&和及]+/).filter(Boolean);
  } else return [];
  const indices: number[] = [];
  for (const token of tokens) {
    if (typeof token === 'number') {
      if (Number.isInteger(token)) indices.push(numbering === 'one' ? token - 1 : token);
      continue;
    }
    const s = String(token ?? '').trim();
    // "A" 或 "A. 北京" 都要认成选项 A
    const bare = s.replace(OPTION_LABEL, '').trim();
    const letter = letterIndex(s) >= 0 ? letterIndex(s) : letterIndex(bare);
    if (letter >= 0) { indices.push(letter); continue; }
    if (/^\d+$/.test(s)) { const n = Number(s); indices.push(numbering === 'one' ? n - 1 : n); continue; }
    // 有些题库直接把选项原文写进 answer（排除空字符串的纯图片选项）
    const byText = options.findIndex((option) => !!option && (option === s || option === bare));
    if (byText >= 0) indices.push(byText);
  }
  return [...new Set(indices.filter((index) => index >= 0 && index < options.length))];
}

export function answerTexts(raw: any): string[] {
  if (Array.isArray(raw)) return raw.map(textOf).filter(Boolean);
  const s = clean(raw);
  if (!s) return [];
  // 多个可接受答案常用 | / 或 分隔
  return s.split(/[|｜;；]|或/).map((part) => part.trim()).filter(Boolean);
}

export function languageOf(raw: any): string {
  const s = String(raw ?? '').trim().replace(/_/g, '-').toLowerCase();
  const map: Record<string, string> = {
    '': '', zh: 'zh-CN', 'zh-cn': 'zh-CN', 'zh-hans': 'zh-CN',
    'zh-hk': 'zh-HK', 'zh-tw': 'zh-TW', 'zh-hant': 'zh-TW',
    en: 'en-US', 'en-us': 'en-US', 'en-gb': 'en-GB',
  };
  return map[s] ?? '';
}

export function difficultyOf(item: any): number {
  const raw = lookup(item, ['difficulty', '难度', 'stars', 'star']);
  const numeric = parseFloat(String(raw ?? ''));
  if (Number.isFinite(numeric)) return Math.min(5, Math.max(1, Math.round(numeric)));
  const word = String(raw ?? '').trim().toLowerCase();
  if (['简单', '容易', '易', 'easy', 'low'].includes(word)) return 2;
  if (['中等', '一般', '普通', 'medium', 'normal'].includes(word)) return 3;
  if (['困难', '难', '较难', 'high', 'hard', 'difficult'].includes(word)) return 4;
  return 2;
}

export function typeOf(item: any, options: string[], answer: any, numbering: 'zero' | 'one'): string {
  const declared = lookup(item, ['type', '题型', '类型', 'questionType']);
  const key = String(declared ?? '').toLowerCase().replace(/[\s_\-]/g, '');
  if (key && QUESTION_TYPES[key]) return QUESTION_TYPES[key];
  if (key) {
    // 题库里常见 "choice" 这种泛指，按选项和答案再判断
    if (options.length >= 2 && ['choice', '选择', 'pick'].includes(key)) {
      return parseSelection(answer, options, numbering).length > 1 ? 'multiple' : 'single';
    }
    throw new Error(`不支持的题型：${declared}`);
  }
  if (options.length >= 2) return parseSelection(answer, options, numbering).length > 1 ? 'multiple' : 'single';
  if (boolWord(answer) !== null) return 'boolean';
  if (clean(answer)) return 'text';
  throw new Error('缺少选项和答案，无法判断题型');
}
