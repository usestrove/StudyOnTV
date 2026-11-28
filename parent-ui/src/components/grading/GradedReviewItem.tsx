import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from '@/components/ui/accordion';
import { Badge } from '@/components/ui/badge';
import { api, resolveMediaUrl } from '@/api';
import { answerOf, MathText, questionImages } from '@/components/bank/shared';

function formatGradedTime(value: unknown) {
  const timestamp = Number(value);
  if (!Number.isFinite(timestamp) || timestamp <= 0) return '';
  return new Date(timestamp).toLocaleString('zh-CN', {
    month: 'numeric',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

export default function GradedReviewItem({ review }: { review: any }) {
  const [open, setOpen] = useState(false);
  const image = useQuery({
    queryKey: ['review', review.id],
    queryFn: () => api('/api/reviews/' + review.id),
    enabled: open && !!review.input?.hasDrawing && !review.input?.drawing,
    staleTime: 5 * 60 * 1000,
  });
  const drawing = review.input?.drawing || image.data?.input?.drawing;
  const qImgs = questionImages(review.question);
  const passed = review.result?.correct;
  return (
    <article className="border-t py-4 first:border-t-0">
      <div className="flex items-start justify-between gap-3">
        <MathText
          text={review.question?.q || '题目已删除'}
          className="min-w-0 whitespace-pre-wrap text-sm font-medium leading-7"
        />
        <Badge variant={passed === false ? 'destructive' : 'default'}>
          {Number(review.score) || 0} 分
        </Badge>
      </div>
      <div className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
        <span>{review.source === 'ai' ? 'AI 批改' : '家长 / Agent 批改'}</span>
        {passed === true && <span>计为答对</span>}
        {passed === false && <span>计为答错</span>}
        {review.gradedAt ? <span>{formatGradedTime(review.gradedAt)}</span> : null}
      </div>
      <Accordion type="single" collapsible onValueChange={v => setOpen(v === 'detail')}>
        <AccordionItem value="detail">
          <AccordionTrigger>查看作答与批改意见</AccordionTrigger>
          <AccordionContent className="space-y-3">
            {qImgs.length > 0 && (
              <div className="flex flex-wrap gap-2">
                {qImgs.map((src, i) => (
                  <img
                    key={i}
                    src={resolveMediaUrl(src)}
                    alt={`题目配图 ${i + 1}`}
                    className="max-h-40 rounded border bg-muted/20 object-contain"
                  />
                ))}
              </div>
            )}
            <div>
              <div className="text-xs text-muted-foreground">孩子的答案</div>
              <MathText
                className="mt-1 whitespace-pre-wrap rounded-lg bg-muted/55 p-3 leading-7"
                text={review.input?.text || ((review.input?.hasDrawing || drawing) ? '手写作答' : '（空）')}
              />
              {(review.input?.hasDrawing || drawing) && (
                image.isLoading ? (
                  <div className="mt-2 text-xs text-muted-foreground">正在读取手写图片…</div>
                ) : drawing ? (
                  <img
                    className="my-3 max-h-96 w-full rounded-lg border bg-white object-contain"
                    alt="孩子的手写答案"
                    src={resolveMediaUrl(drawing)}
                  />
                ) : null
              )}
            </div>
            {review.feedback && (
              <div>
                <div className="text-xs text-muted-foreground">批改意见</div>
                <p className="mt-1 whitespace-pre-wrap leading-7">{review.feedback}</p>
              </div>
            )}
            <div>
              <div className="text-xs text-muted-foreground">参考答案</div>
              <MathText
                className="mt-1 whitespace-pre-wrap leading-7"
                text={review.question?.modelAnswer || answerOf(review.question || {})}
              />
            </div>
          </AccordionContent>
        </AccordionItem>
      </Accordion>
    </article>
  );
}
