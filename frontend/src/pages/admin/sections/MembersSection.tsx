import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Users, Link as LinkIcon, KeyRound, Trash2, Copy, Check } from 'lucide-react'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { ConfirmDialog } from '@/components/shared/ConfirmDialog'
import { useAuthStore } from '@/stores/auth-store'
import {
  useFamilyMembers,
  useDeleteMember,
  useGenerateActivationLink,
  useResetMemberPassword,
} from '@/features/family/hooks'

type GeneratedLink = { memberId: number; url: string } | null

export function MembersSection() {
  const { t } = useTranslation()
  const currentUser = useAuthStore((s) => s.user)
  const { data: members, isLoading } = useFamilyMembers()
  const deleteMember = useDeleteMember()
  const generateActivation = useGenerateActivationLink()
  const resetPassword = useResetMemberPassword()

  const [link, setLink] = useState<GeneratedLink>(null)
  const [copied, setCopied] = useState(false)
  const [deletingId, setDeletingId] = useState<number | null>(null)

  function showLink(memberId: number, path: string) {
    setLink({ memberId, url: `${window.location.origin}${path}` })
    setCopied(false)
  }

  async function handleCopy() {
    if (!link) return
    try {
      await navigator.clipboard.writeText(link.url)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch {
      /* clipboard may be unavailable in non-https dev contexts; user can select-copy */
    }
  }

  function handleConfirmDelete() {
    if (deletingId == null) return
    deleteMember.mutate(deletingId, {
      onSuccess: () => setDeletingId(null),
    })
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <Users className="size-5" />
          {t('admin.members.title')}
        </CardTitle>
        <CardDescription>{t('admin.members.description')}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {isLoading ? (
          <p className="text-sm text-muted-foreground">{t('admin.members.loading')}</p>
        ) : !members || members.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t('admin.members.noMembers')}</p>
        ) : (
          <ul className="divide-y rounded-lg border">
            {members.map((m) => {
              const isSelf = m.id === currentUser?.memberId
              const isAdminMember = !m.managed
              const status = isAdminMember
                ? 'statusAdmin'
                : m.activated
                  ? 'statusActive'
                  : m.hasLogin
                    ? 'statusPending'
                    : 'statusManaged'

              return (
                <li
                  key={m.id}
                  className="flex flex-col gap-3 p-3 sm:flex-row sm:items-center sm:justify-between"
                >
                  <div className="flex items-center gap-3 min-w-0">
                    <div
                      className="size-9 shrink-0 rounded-full"
                      style={{ backgroundColor: m.avatarColor }}
                      aria-hidden
                    />
                    <div className="min-w-0">
                      <div className="flex items-center gap-2">
                        <p className="font-medium truncate">{m.displayName}</p>
                        {isSelf && (
                          <span className="text-xs text-muted-foreground">
                            {t('admin.members.you')}
                          </span>
                        )}
                      </div>
                      <p className="text-xs text-muted-foreground truncate">
                        <span className="font-mono">
                          {m.loginName ?? t('admin.members.noLogin')}
                        </span>
                        <span className="mx-2">·</span>
                        <span>{t(`admin.members.${status}`)}</span>
                      </p>
                    </div>
                  </div>

                  <div className="flex flex-wrap gap-2 sm:justify-end">
                    {/* Create login OR regenerate activation link (non-activated) */}
                    {m.managed && !m.activated && (
                      <Button
                        variant="outline"
                        size="sm"
                        disabled={generateActivation.isPending}
                        onClick={() => {
                          generateActivation.mutate(m.id, {
                            onSuccess: (d) => showLink(m.id, d.activationLink),
                          })
                        }}
                      >
                        <LinkIcon className="size-3.5" />
                        {m.hasLogin
                          ? t('admin.members.regenerateLink')
                          : t('admin.members.createLogin')}
                      </Button>
                    )}

                    {/* Reset password (only for active accounts that the admin doesn't own) */}
                    {m.activated && !isSelf && (
                      <Button
                        variant="outline"
                        size="sm"
                        disabled={resetPassword.isPending}
                        onClick={() => {
                          resetPassword.mutate(m.id, {
                            onSuccess: (d) => showLink(m.id, d.resetLink),
                          })
                        }}
                      >
                        <KeyRound className="size-3.5" />
                        {t('admin.members.resetPassword')}
                      </Button>
                    )}

                    {/* Delete (anyone but self and the seed admin) */}
                    {!isSelf && m.managed && (
                      <Button
                        variant="ghost"
                        size="sm"
                        className="text-destructive hover:text-destructive"
                        onClick={() => setDeletingId(m.id)}
                      >
                        <Trash2 className="size-3.5" />
                        {t('admin.members.delete')}
                      </Button>
                    )}
                  </div>
                </li>
              )
            })}
          </ul>
        )}

        {link && (
          <div className="rounded-lg border bg-muted p-3 space-y-2">
            <p className="text-xs font-medium">{t('admin.members.linkLabel')}</p>
            <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
              <code className="flex-1 text-xs break-all rounded bg-background px-2 py-1.5">
                {link.url}
              </code>
              <Button size="sm" variant="outline" onClick={handleCopy}>
                {copied ? <Check className="size-3.5" /> : <Copy className="size-3.5" />}
                {copied ? t('admin.members.copied') : t('admin.members.copyLink')}
              </Button>
            </div>
          </div>
        )}
      </CardContent>

      <ConfirmDialog
        open={deletingId !== null}
        onOpenChange={(o) => { if (!o) setDeletingId(null) }}
        title={t('admin.members.deleteConfirmTitle')}
        description={t('admin.members.deleteConfirmDesc')}
        onConfirm={handleConfirmDelete}
        loading={deleteMember.isPending}
        variant="destructive"
      />
    </Card>
  )
}
