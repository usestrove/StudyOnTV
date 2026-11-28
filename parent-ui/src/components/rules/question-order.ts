export type QuestionOrderMode = 'coverage' | 'wrong_first' | 'new_first' | 'random';

export type QuestionOrderFlags = {
  wrongFirst: boolean;
  newFirst: boolean;
  coverageFirst: boolean;
};

export const QUESTION_ORDER_OPTIONS: {
  value: QuestionOrderMode;
  label: string;
  preview: string;
}[] = [
  {
    value: 'coverage',
    label: '先把没做对的题刷一遍（推荐）',
    preview: '没答对过的题优先出，整本题库轮流练到，避免总抽到同一批旧题',
  },
  {
    value: 'wrong_first',
    label: '先重做答错的题',
    preview: '刚答错的题最先回来重练，答对后再练其余没做对的新题',
  },
  {
    value: 'new_first',
    label: '先做没见过的新题',
    preview: '一次都没见过的全新题目排最前，新题刷完后再练旧题和错题',
  },
  {
    value: 'random',
    label: '完全随机抽题',
    preview: '每次从范围内随机抽取，不区分新题、旧题和错题',
  },
];

/** 将底层 3 个兼容布尔位映射为家长一眼能懂的单一出题顺序模式。 */
export function resolveQuestionOrderMode(flags: Partial<QuestionOrderFlags>): QuestionOrderMode {
  if (flags.wrongFirst) return 'wrong_first';
  if (flags.newFirst) return 'new_first';
  if (flags.coverageFirst !== false) return 'coverage';
  return 'random';
}

export function questionOrderPatch(mode: QuestionOrderMode): QuestionOrderFlags {
  switch (mode) {
    case 'wrong_first':
      return { wrongFirst: true, newFirst: false, coverageFirst: true };
    case 'new_first':
      return { wrongFirst: false, newFirst: true, coverageFirst: true };
    case 'random':
      return { wrongFirst: false, newFirst: false, coverageFirst: false };
    case 'coverage':
    default:
      return { wrongFirst: false, newFirst: false, coverageFirst: true };
  }
}
