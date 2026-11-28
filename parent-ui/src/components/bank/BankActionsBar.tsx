import { Download, Globe, MoreHorizontal, Plus, Sparkles, Trash2, Upload } from 'lucide-react';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';

type Props = {
  selectedCount: number;
  totalCount: number;
  loading: boolean;
  exporting: boolean;
  bulkPausePending: boolean;
  bulkDeletePending: boolean;
  clearBankPending: boolean;
  onCreate: () => void;
  onOpenMarket: () => void;
  onOpenImport: () => void;
  onOpenAi: () => void;
  onExport: () => void;
  onPauseSelected: () => void;
  onDeleteSelected: () => void;
  onClearBank: () => void;
};

export default function BankActionsBar(props: Props) {
  return (
    <>
      <Button size="sm" onClick={props.onCreate}>
        <Plus />
        新增题目
      </Button>
      <Button variant="outline" size="sm" onClick={props.onOpenMarket}>
        <Globe />
        在线题库市场
      </Button>
      <Button variant="outline" size="sm" onClick={props.onOpenImport}>
        <Upload />
        导入 Word/PDF/JSON
      </Button>
      <Button variant="outline" size="sm" onClick={props.onOpenAi}>
        <Sparkles />
        AI 出题
      </Button>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="outline" size="icon" className="size-8" aria-label="更多题库操作">
            <MoreHorizontal />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="min-w-44">
          <DropdownMenuLabel>导出</DropdownMenuLabel>
          <DropdownMenuItem disabled={props.exporting} onSelect={props.onExport}>
            <Download />
            {props.exporting ? '正在导出…' : '导出全部题目'}
          </DropdownMenuItem>
          {props.selectedCount > 0 && (
            <>
              <DropdownMenuSeparator />
              <DropdownMenuLabel>已选 {props.selectedCount} 题</DropdownMenuLabel>
              <DropdownMenuItem disabled={props.bulkPausePending} onSelect={props.onPauseSelected}>
                暂停选中
              </DropdownMenuItem>
              <DropdownMenuItem
                variant="destructive"
                disabled={props.bulkDeletePending}
                onSelect={props.onDeleteSelected}
              >
                <Trash2 />
                删除选中
              </DropdownMenuItem>
            </>
          )}
          <DropdownMenuSeparator />
          <DropdownMenuItem
            variant="destructive"
            disabled={props.loading || props.clearBankPending || !props.totalCount}
            onSelect={() => {
              if (window.confirm('确定清空全部题库（包括已暂停的题目）？此操作无法撤销，建议先导出备份。')) {
                props.onClearBank();
              }
            }}
          >
            <Trash2 />
            清空题库
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </>
  );
}
