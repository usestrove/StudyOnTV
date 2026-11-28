import type { ReactNode } from 'react';
import { Card, CardContent } from '@/components/ui/card';
import { Item, ItemActions, ItemContent, ItemDescription, ItemGroup, ItemTitle } from '@/components/ui/item';
import { SectionHeading } from '@/components/ui/section-heading';

export function SettingsCard({
  title,
  description,
  icon: Icon,
  children,
  footer,
}: {
  title: string;
  description?: string;
  icon: any;
  children: ReactNode;
  footer?: ReactNode;
}) {
  return (
    <section className="space-y-2">
      <SectionHeading icon={Icon} title={title} description={description} />
      <Card className="gap-0 overflow-hidden py-0 shadow-xs">
        <CardContent className="px-4 sm:px-5">
          <ItemGroup className="divide-y divide-border/60">{children}</ItemGroup>
          {footer && <div className="flex justify-end gap-2 border-t py-3">{footer}</div>}
        </CardContent>
      </Card>
    </section>
  );
}

export function SettingItem({
  title,
  description,
  children,
  wideControl = false,
}: {
  title: string;
  description?: ReactNode;
  children: ReactNode;
  wideControl?: boolean;
}) {
  return (
    <Item
      size="sm"
      className={`items-start gap-3 rounded-none px-0 py-4 ${wideControl ? 'flex-col sm:flex-row' : ''}`}
    >
      <ItemContent className={wideControl ? 'w-full min-w-0 gap-1 sm:w-auto sm:flex-1' : 'min-w-0 gap-1'}>
        <ItemTitle>{title}</ItemTitle>
        {description && (
          <ItemDescription className="line-clamp-none text-xs leading-5">{description}</ItemDescription>
        )}
      </ItemContent>
      <ItemActions
        className={`min-w-0 flex-wrap justify-end ${wideControl ? 'ms-0 w-full sm:ms-auto sm:w-auto' : 'ms-auto'}`}
      >
        {children}
      </ItemActions>
    </Item>
  );
}
