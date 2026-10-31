import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Bell, CheckCheck, Loader2, MailOpen } from 'lucide-react'
import { useState } from 'react'
import { messagesApi } from '@/api/messages'
import { Loading } from '@/components/Loading'
import { PageHeader } from '@/components/PageHeader'
import { Pagination } from '@/components/Pagination'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyTitle,
} from '@/components/ui/empty'
import { toast } from '@/components/ui/toast'
import { formatDateTime, isReadStatus } from '@/lib/format'
import { messageTypeTone } from '@/lib/status'

const PAGE_SIZE = 10

export default function MessagesPage() {
  const queryClient = useQueryClient()
  const [page, setPage] = useState(1)

  const messagesQuery = useQuery({
    queryKey: ['messages', page],
    queryFn: () => messagesApi.list({ page, size: PAGE_SIZE }),
  })

  const unreadQuery = useQuery({
    queryKey: ['messages', 'unread-count'],
    queryFn: messagesApi.unreadCount,
  })

  const readMutation = useMutation({
    mutationFn: (id: number) => messagesApi.read(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['messages'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })

  const readAllMutation = useMutation({
    mutationFn: messagesApi.readAll,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['messages'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
      toast.add({ title: '已全部标记为已读', type: 'success' })
    },
  })

  const unreadCount = unreadQuery.data ?? 0

  return (
    <div>
      <PageHeader
        title="消息中心"
        description={`预约、取件、逾期与报修通知都会在这里提醒你${unreadCount > 0 ? `，当前有 ${unreadCount} 条未读` : ''}。`}
        action={
          <Button
            type="button"
            variant="outline"
            disabled={unreadCount === 0 || readAllMutation.isPending}
            onClick={() => readAllMutation.mutate()}
          >
            {readAllMutation.isPending ? (
              <Loader2 className="size-4 animate-spin" />
            ) : (
              <CheckCheck />
            )}
            全部已读
          </Button>
        }
      />

      {messagesQuery.isLoading ? (
        <Loading />
      ) : messagesQuery.data && messagesQuery.data.records.length > 0 ? (
        <>
          <div className="space-y-3">
            {messagesQuery.data.records.map((message) => {
              const read = isReadStatus(message.readStatus)
              return (
                <div
                  key={message.id}
                  className={
                    read
                      ? 'rounded-2xl border border-border bg-card p-4 sm:p-5'
                      : 'rounded-2xl border border-primary/40 bg-primary/5 p-4 sm:p-5'
                  }
                >
                  <div className="flex flex-wrap items-start justify-between gap-3">
                    <div className="min-w-0 flex-1">
                      <div className="flex flex-wrap items-center gap-2">
                        {!read && (
                          <span className="size-2 rounded-full bg-primary" />
                        )}
                        <Badge tone={messageTypeTone(message.type)}>
                          {message.typeName}
                        </Badge>
                        <h3
                          className={
                            read
                              ? 'font-medium text-foreground'
                              : 'font-semibold text-foreground'
                          }
                        >
                          {message.title}
                        </h3>
                      </div>
                      <p className="mt-2 text-sm leading-relaxed whitespace-pre-wrap text-foreground/80">
                        {message.content}
                      </p>
                      <p className="mt-2 text-xs text-muted-foreground">
                        {formatDateTime(message.createTime)} ·{' '}
                        {message.channel === 'SMS' ? '短信' : '站内消息'}
                      </p>
                    </div>
                    {read ? (
                      <span className="flex shrink-0 items-center gap-1 text-xs text-muted-foreground">
                        <MailOpen className="size-3.5" />
                        已读
                      </span>
                    ) : (
                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        disabled={readMutation.isPending}
                        onClick={() => readMutation.mutate(message.id)}
                      >
                        {readMutation.isPending &&
                        readMutation.variables === message.id ? (
                          <Loader2 className="size-3.5 animate-spin" />
                        ) : (
                          <CheckCheck />
                        )}
                        标记已读
                      </Button>
                    )}
                  </div>
                </div>
              )
            })}
          </div>
          <div className="mt-6">
            <Pagination
              page={messagesQuery.data.page}
              size={messagesQuery.data.size || PAGE_SIZE}
              total={messagesQuery.data.total}
              onChange={setPage}
            />
          </div>
        </>
      ) : (
        <div className="rounded-2xl border border-dashed border-border bg-card/50 p-12">
          <Empty>
            <EmptyHeader>
              <Bell className="mb-2 size-10 text-muted-foreground" />
              <EmptyTitle>暂无消息</EmptyTitle>
              <EmptyDescription>
                预约成功、取件提醒和逾期通知会及时送达这里。
              </EmptyDescription>
            </EmptyHeader>
          </Empty>
        </div>
      )}
    </div>
  )
}
