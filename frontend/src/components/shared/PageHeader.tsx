interface PageHeaderProps {
  surtitle?: string
  title: string
  actions?: React.ReactNode
}

export function PageHeader({ surtitle, title, actions }: PageHeaderProps) {
  return (
    <div className="flex items-center justify-between mb-6">
      <div>
        {surtitle && (
          <p className="text-[13px] text-muted-foreground mb-1" style={{ fontWeight: 500 }}>
            {surtitle}
          </p>
        )}
        <h1 className="text-[28px] text-foreground" style={{ fontWeight: 700, lineHeight: 1.2 }}>
          {title}
        </h1>
      </div>
      {actions && <div className="flex items-center gap-2">{actions}</div>}
    </div>
  )
}
