import { ChevronRight, Copy, Image as ImageIcon, Pencil, Play, Trash2 } from 'lucide-react';
import { resolveMediaUrl } from '@/api';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Toolbar, ToolbarGroup } from '@/components/ui/toolbar';
import { MathText, Stars, answerOf, dot, labels, questionImages } from './shared';

type Props = {
  items: any[];
  selected: string[];
  allSelected: boolean;
  togglePending?: boolean;
  onToggle: (id: string) => void;
  onToggleEnabled: (id: string, enabled: boolean) => void;
  onSelectAll: (checked: boolean) => void;
  onOpen: (id: string) => void;
  onEdit: (q?: any, copy?: boolean) => void;
  onRemove: (q: any) => void;
};

function firstPreviewMedia(q: any): string {
  const imgs = questionImages(q);
  if (imgs.length) return imgs[0];
  if (Array.isArray(q?.optionImages)) {
    const found = q.optionImages.find(
      (s: any) => typeof s === 'string' && /^(\/api\/media\/|https?:\/\/|data:image\/)/.test(s.trim()),
    );
    if (found) return found.trim();
  }
  return '';
}

export default function QuestionListRows(props: Props) {
  const { items, selected, allSelected } = props;
  return (
    <>
      <div className="hidden md:block">
        <Table className="min-w-[800px] text-sm">
          <TableHeader className="bg-muted/50 text-xs text-muted-foreground">
            <TableRow className="hover:bg-transparent">
              <TableHead className="w-10 px-3">
                <Checkbox
                  aria-label="全选当前页"
                  checked={allSelected}
                  onCheckedChange={checked => props.onSelectAll(checked === true)}
                />
              </TableHead>
              <TableHead className="px-3">题目</TableHead>
              <TableHead className="w-20 px-3">题型</TableHead>
              <TableHead className="w-20 px-3">科目</TableHead>
              <TableHead className="w-20 px-3">年级</TableHead>
              <TableHead className="w-24 px-3">难度</TableHead>
              <TableHead className="w-[18%] px-3">答案</TableHead>
              <TableHead className="w-40 px-3 text-right">操作</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {items.map((q: any) => {
              const thumb = firstPreviewMedia(q);
              return (
                <TableRow
                  key={q.id}
                  className={q.enabled === false ? 'cursor-pointer opacity-75' : 'cursor-pointer'}
                  onClick={() => props.onOpen(q.id)}
                >
                  <TableCell className="px-3" onClick={e => e.stopPropagation()}>
                    <Checkbox
                      aria-label="选择题目"
                      checked={selected.includes(q.id)}
                      onCheckedChange={() => props.onToggle(q.id)}
                    />
                  </TableCell>
                  <TableCell className="max-w-0 px-3">
                    <div className="flex min-w-0 items-center gap-2">
                      <span className={'size-1.5 shrink-0 rounded-full ' + (dot[q.type] || dot.single)} />
                      <MathText text={q.q} className="truncate" />
                      {q.enabled === false && (
                        <Badge variant="outline" className="shrink-0 px-1.5 py-0 text-[10px]">
                          暂停
                        </Badge>
                      )}
                      {thumb && (
                        <span className="inline-flex shrink-0 items-center gap-1" title="含配图">
                          <img
                            src={resolveMediaUrl(thumb)}
                            alt=""
                            loading="lazy"
                            className="size-5 rounded border bg-muted/20 object-cover"
                          />
                          <ImageIcon className="size-3.5 text-muted-foreground sr-only" aria-label="含配图" />
                        </span>
                      )}
                    </div>
                  </TableCell>
                  <TableCell className="px-3">
                    <Badge variant="secondary">{labels[q.type] || '单选'}</Badge>
                  </TableCell>
                  <TableCell className="px-3 text-muted-foreground">{q.subject || '—'}</TableCell>
                  <TableCell className="px-3 text-muted-foreground">{q.level || '—'}</TableCell>
                  <TableCell className="px-3">
                    <Stars n={q.difficulty || 2} />
                  </TableCell>
                  <TableCell className="px-3">
                    <MathText text={answerOf(q)} className="truncate text-muted-foreground" />
                  </TableCell>
                  <TableCell className="px-3" onClick={e => e.stopPropagation()}>
                    <div className="flex items-center justify-end gap-1">
                      {q.enabled === false && (
                        <Button
                          size="sm"
                          variant="outline"
                          className="h-8 px-2 text-xs"
                          disabled={props.togglePending}
                          onClick={() => props.onToggleEnabled(q.id, true)}
                        >
                          <Play className="size-3.5" />
                          恢复
                        </Button>
                      )}
                      <Button
                        size="icon"
                        variant="ghost"
                        className="size-8"
                        aria-label="编辑题目"
                        onClick={() => props.onEdit(q)}
                      >
                        <Pencil />
                      </Button>
                      <Button
                        size="icon"
                        variant="ghost"
                        className="size-8"
                        aria-label="复制为新题"
                        onClick={() => props.onEdit(q, true)}
                      >
                        <Copy />
                      </Button>
                      <Button
                        size="icon"
                        variant="ghost"
                        className="size-8"
                        aria-label="删除题目"
                        onClick={() => props.onRemove(q)}
                      >
                        <Trash2 className="text-destructive/60" />
                      </Button>
                    </div>
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      </div>

      <div className="divide-y md:hidden">
        {items.map((q: any) => {
          const thumb = firstPreviewMedia(q);
          return (
            <div key={q.id} className="space-y-2 px-4 py-3">
              <Toolbar className="gap-2">
                <ToolbarGroup className="min-w-0">
                  <Checkbox
                    aria-label="选择题目"
                    checked={selected.includes(q.id)}
                    onCheckedChange={() => props.onToggle(q.id)}
                  />
                  <Badge variant="secondary">{labels[q.type] || '单选'}</Badge>
                  <Stars n={q.difficulty || 2} />
                  {q.enabled === false && (
                    <Badge variant="outline" className="px-1.5 py-0 text-[10px]">
                      已暂停
                    </Badge>
                  )}
                </ToolbarGroup>
                <ToolbarGroup className="ms-auto">
                  <Button size="icon" variant="ghost" aria-label="查看题目" onClick={() => props.onOpen(q.id)}>
                    <ChevronRight />
                  </Button>
                </ToolbarGroup>
              </Toolbar>
              <div className="flex items-start gap-2.5">
                <MathText text={q.q} className="min-w-0 flex-1 text-sm font-medium leading-6" />
                {thumb && (
                  <img
                    src={resolveMediaUrl(thumb)}
                    alt="题目配图"
                    loading="lazy"
                    className="size-12 shrink-0 rounded border bg-muted/20 object-contain"
                  />
                )}
              </div>
              <MathText text={'答案：' + answerOf(q)} className="truncate text-xs text-muted-foreground" />
              <Toolbar className="gap-1">
                <ToolbarGroup>
                  <span className="truncate text-xs text-muted-foreground">
                    {q.subject || '未分类'}
                    {q.level ? ' · ' + q.level : ''}
                  </span>
                </ToolbarGroup>
                <ToolbarGroup className="ms-auto">
                  {q.enabled === false && (
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={props.togglePending}
                      onClick={() => props.onToggleEnabled(q.id, true)}
                    >
                      <Play />
                      恢复
                    </Button>
                  )}
                  <Button size="sm" variant="ghost" onClick={() => props.onEdit(q)}>
                    <Pencil />
                    编辑
                  </Button>
                  <Button size="sm" variant="ghost" onClick={() => props.onEdit(q, true)}>
                    <Copy />
                    复制
                  </Button>
                  <Button size="sm" variant="ghost" className="text-destructive" onClick={() => props.onRemove(q)}>
                    <Trash2 />
                    删除
                  </Button>
                </ToolbarGroup>
              </Toolbar>
            </div>
          );
        })}
      </div>
    </>
  );
}
