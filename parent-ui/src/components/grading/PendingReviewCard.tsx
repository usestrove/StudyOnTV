import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from '@/components/ui/accordion';
import { Button } from '@/components/ui/button';
import { Field, FieldDescription, FieldGroup, FieldLabel } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { api, resolveMediaUrl } from '@/api';
import { answerOf, MathText, questionImages } from '@/components/bank/shared';

export default function PendingReviewCard({
  review,
  sessionId,
  ai,
}: {
  review: any;
  sessionId: number;
  ai: boolean;
}) {
  const [score, setScore] = useState('60');
  const [feedback, setFeedback] = useState('');
  const client = useQueryClient();
  const submit = useMutation({
    mutationFn: (body: any) => api('/api/reviews/' + review.id, 'POST', body),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['reviews', sessionId] });
      client.invalidateQueries({ queryKey: ['reviews-graded', sessionId] });
      client.invalidateQueries({ queryKey: ['state', sessionId] });
      toast.success('评分已提交');
    },
    onError: (error: any) => toast.error(error.message),
  });
  const retry = useMutation({
    mutationFn: () => api('/api/reviews/' + review.id + '/retry', 'POST', {}),
    onSuccess: () => toast.success('已提交 AI 重试'),
    onError: (error: any) => toast.error(error.message),
  });
  const qImgs = questionImages(review.question);

  return (
    <article className="border-t py-4 first:border-t-0">
      <MathText text={review.question.q} className="whitespace-pre-wrap text-sm font-medium leading-7" />
      {qImgs.length > 0 && (
        <div className="mt-3 flex flex-wrap gap-2">
          {qImgs.map((src, i) => (
            <img
              key={i}
              src={resolveMediaUrl(src)}
              alt={`题目配图 ${i + 1}`}
              className="max-h-48 rounded border bg-muted/20 object-contain"
            />
          ))}
        </div>
      )}
      <FieldGroup className="mt-4 gap-4">
        <Field>
          <FieldLabel>孩子的答案</FieldLabel>
          <MathText
            className="whitespace-pre-wrap rounded-lg bg-muted/55 p-3 leading-7"
            text={review.input.text || '手写作答'}
          />
          {review.input.drawing && (
            <img
              className="my-3 max-h-96 w-full rounded-lg border bg-white object-contain"
              alt="孩子的手写答案"
              src={resolveMediaUrl(review.input.drawing)}
            />
          )}
        </Field>
        <Accordion type="single" collapsible>
          <AccordionItem value="answer">
            <AccordionTrigger>参考答案与评分标准</AccordionTrigger>
            <AccordionContent className="space-y-2">
              <MathText
                className="whitespace-pre-wrap leading-7"
                text={review.question.modelAnswer || answerOf(review.question)}
              />
              <MathText
                className="whitespace-pre-wrap leading-7 text-muted-foreground"
                text={review.question.rubric || ''}
              />
            </AccordionContent>
          </AccordionItem>
        </Accordion>
        {review.feedback && <FieldDescription>{review.feedback}</FieldDescription>}
        <FieldGroup className="grid gap-4 sm:grid-cols-[120px_minmax(0,1fr)]">
          <Field>
            <FieldLabel htmlFor={`score-${review.id}`}>分数</FieldLabel>
            <Input
              id={`score-${review.id}`}
              aria-label="分数"
              className="w-full text-center"
              type="number"
              min={0}
              max={100}
              value={score}
              onChange={event => setScore(event.target.value)}
            />
          </Field>
          <Field>
            <FieldLabel htmlFor={`feedback-${review.id}`}>批改意见</FieldLabel>
            <Textarea
              id={`feedback-${review.id}`}
              value={feedback}
              onChange={event => setFeedback(event.target.value)}
              rows={3}
            />
          </Field>
        </FieldGroup>
      </FieldGroup>
      <div className="mt-4 flex justify-end gap-2">
        {ai && (
          <Button variant="outline" disabled={retry.isPending} onClick={() => retry.mutate()}>
            AI 重试
          </Button>
        )}
        <Button
          disabled={submit.isPending}
          onClick={() => {
            const value = Number(score);
            if (score === '' || !Number.isFinite(value) || value < 0 || value > 100) {
              return toast.error('请输入 0–100 分');
            }
            submit.mutate({ score: value, feedback });
          }}
        >
          提交评分
        </Button>
      </div>
    </article>
  );
}
