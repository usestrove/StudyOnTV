import type { ComponentType, ReactNode } from 'react';
import {
  BookOpen,
  ChevronLeft,
  ChevronRight,
  Globe,
  Plus,
  Search,
  Sparkles,
  Trash2,
  Upload,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { FieldDescription } from '@/components/ui/field';
import { Toolbar, ToolbarGroup, ToolbarSeparator } from '@/components/ui/toolbar';
import QuestionListRows from './QuestionListRows';
import { Count } from './shared';

type Props = {
  items: any[];
  total: number;
  page: number;
  pages: number;
  loading: boolean;
  hasFilters: boolean;
  selected: string[];
  pausedCount: number;
  showAll: boolean;
  bankFilter: string;
  bulkPending: boolean;
  deleteBankPending: boolean;
  onPage: (page: number) => void;
  onToggle: (id: string) => void;
  onToggleShowAll: () => void;
  onToggleEnabled: (id: string, enabled: boolean) => void;
  onSelectPage: () => void;
  onSelectAll: (checked: boolean) => void;
  onClearSelection: () => void;
  onOpen: (id: string) => void;
  onEdit: (q?: any, copy?: boolean) => void;
  onRemove: (q: any) => void;
  onDeleteBank: (bank: string) => void;
  onBulkPause: (enabled: boolean) => void;
  onBulkDelete: () => void;
  onReset: () => void;
  onCreate: () => void;
  onOpenAi: () => void;
  onOpenMarket: () => void;
  onOpenImport: () => void;
};

/** 空状态：没有题目、筛选没有结果时，给出下一步该做什么 */
function EmptyState({
  icon: Icon,
  title,
  description,
  action,
}: {
  icon: ComponentType<any>;
  title: string;
  description: string;
  action?: ReactNode;
}) {
  return (
    <div className="flex flex-col items-center justify-center gap-3 py-16 text-center">
      <div className="rounded-full bg-muted p-3">
        <Icon className="size-6 text-muted-foreground" />
      </div>
      <div>
        <p className="text-sm font-medium">{title}</p>
        <p className="mt-1 text-xs text-muted-foreground">{description}</p>
      </div>
      {action}
    </div>
  );
}

/** 题目列表：桌面表格 / 手机卡片共用同一页数据，勾选只作用于当前页。 */
export default function QuestionList(props: Props) {
  const {
    items,
    total,
    page,
    pages,
    loading,
    hasFilters,
    selected,
    pausedCount,
    showAll,
    bankFilter,
    bulkPending,
    deleteBankPending,
  } = props;
  const allSelected = items.length > 0 && items.every((q: any) => selected.includes(q.id));
  return (
    <Card className="mt-4 gap-0 overflow-hidden py-0 shadow-none">
      <Toolbar aria-label="题目批量操作工具栏" className="flex-wrap gap-2 border-b px-3 py-2.5 sm:px-4">
        {selected.length > 0 ? (
          <>
            <ToolbarGroup>
              <Count value={selected.length} suffix="道已选" />
              <Button size="sm" variant="ghost" onClick={props.onSelectPage}>
                选择本页
              </Button>
              <Button size="sm" variant="ghost" onClick={props.onClearSelection}>
                取消选择
              </Button>
            </ToolbarGroup>
            <ToolbarSeparator orientation="vertical" className="max-sm:hidden" />
            <ToolbarGroup className="ms-auto">
              <Button size="sm" variant="ghost" disabled={bulkPending} onClick={() => props.onBulkPause(false)}>
                暂停
              </Button>
              <Button size="sm" variant="ghost" disabled={bulkPending} onClick={() => props.onBulkPause(true)}>
                恢复
              </Button>
              <Button
                size="sm"
                variant="destructive"
                disabled={bulkPending}
                onClick={() => {
                  if (window.confirm(`确定删除选中的 ${selected.length} 道题？此操作无法撤销`)) {
                    props.onBulkDelete();
                  }
                }}
              >
                <Trash2 />
                删除
              </Button>
            </ToolbarGroup>
          </>
        ) : (
          <>
            <ToolbarGroup>
              <Count value={total} suffix="道题目" />
              {pausedCount > 0 && (
                <Button
                  size="sm"
                  variant={showAll ? 'secondary' : 'outline'}
                  onClick={props.onToggleShowAll}
                >
                  {showAll ? `隐藏已暂停（${pausedCount}）` : `查看已暂停（${pausedCount}）`}
                </Button>
              )}
            </ToolbarGroup>
            <ToolbarGroup className="ms-auto">
              {bankFilter !== 'all' && (
                <Button
                  size="sm"
                  variant="ghost"
                  disabled={deleteBankPending}
                  onClick={() => {
                    if (confirm(`删除整个「${bankFilter}」题库（包含暂停题）？请先导出备份。`)) {
                      props.onDeleteBank(bankFilter);
                    }
                  }}
                >
                  删除此题库
                </Button>
              )}
              <Button size="sm" variant="outline" disabled={!items.length} onClick={props.onSelectPage}>
                选择本页
              </Button>
            </ToolbarGroup>
          </>
        )}
      </Toolbar>

      <QuestionListRows
        items={items}
        selected={selected}
        allSelected={allSelected}
        togglePending={bulkPending}
        onToggle={props.onToggle}
        onToggleEnabled={props.onToggleEnabled}
        onSelectAll={props.onSelectAll}
        onOpen={props.onOpen}
        onEdit={props.onEdit}
        onRemove={props.onRemove}
      />

      {!items.length &&
        (loading ? (
          <div className="px-6 py-16 text-center">
            <FieldDescription>正在加载题库…</FieldDescription>
          </div>
        ) : total === 0 && !hasFilters && pausedCount > 0 && !showAll ? (
          <EmptyState
            icon={BookOpen}
            title={`当前有 ${pausedCount} 道已暂停的题目`}
            description="已暂停的题目默认隐藏，点击下方按钮可查看并一键恢复"
            action={
              <Button size="sm" onClick={props.onToggleShowAll}>
                查看已暂停题目
              </Button>
            }
          />
        ) : total === 0 && !hasFilters ? (
          <EmptyState
            icon={BookOpen}
            title="题库还没有题目"
            description="可以从在线题库市场下载、导入 Word/PDF/JSON、AI 出题或手动新增"
            action={
              <div className="flex flex-wrap justify-center gap-2">
                <Button size="sm" onClick={props.onOpenMarket}>
                  <Globe className="size-3.5" />
                  在线题库市场
                </Button>
                <Button variant="outline" size="sm" onClick={props.onOpenImport}>
                  <Upload className="size-3.5" />
                  导入文档/JSON
                </Button>
                <Button variant="outline" size="sm" onClick={props.onOpenAi}>
                  <Sparkles className="size-3.5" />
                  AI 出题
                </Button>
                <Button variant="outline" size="sm" onClick={props.onCreate}>
                  <Plus className="size-3.5" />
                  手动新增
                </Button>
              </div>
            }
          />
        ) : (
          <EmptyState
            icon={Search}
            title="没有符合条件的题目"
            description="尝试调整筛选条件或搜索关键词"
            action={
              hasFilters ? (
                <Button variant="outline" size="sm" onClick={props.onReset}>
                  清除所有筛选
                </Button>
              ) : undefined
            }
          />
        ))}

      <Toolbar aria-label="分页工具栏" className="flex-wrap gap-2 border-t px-3 py-2.5 sm:px-4">
        <ToolbarGroup>
          <span className="text-xs text-muted-foreground">
            共 <b className="text-foreground">{total}</b> 题
          </span>
        </ToolbarGroup>
        <ToolbarGroup className="ms-auto">
          <Button size="sm" variant="outline" disabled={page === 0} onClick={() => props.onPage(page - 1)}>
            <ChevronLeft />
            上一页
          </Button>
          <span className="px-1 text-xs tabular-nums text-muted-foreground">
            <b className="font-medium">{page + 1}</b> / {pages}
          </span>
          <Button size="sm" variant="outline" disabled={page + 1 >= pages} onClick={() => props.onPage(page + 1)}>
            下一页
            <ChevronRight />
          </Button>
        </ToolbarGroup>
      </Toolbar>
    </Card>
  );
}
