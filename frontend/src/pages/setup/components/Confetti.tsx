import { useState } from 'react'

interface ConfettiProps {
  count?: number
}

/**
 * Pure-CSS confetti burst. We generate a batch of positioned pieces once
 * via a lazy `useState` initializer so re-renders don't restart the
 * animation (and so the randomized layout stays out of the render body,
 * which must be pure). The parent must be `position: relative`; each piece
 * anchors to the parent's top-center and animates outward. The stylesheet
 * honors `prefers-reduced-motion` and hides all pieces in that mode.
 */
export function Confetti({ count = 40 }: ConfettiProps) {
  const [pieces] = useState(() => {
    const palette = ['#6366f1', '#ec4899', '#10b981', '#f59e0b', '#ef4444', '#3b82f6']
    return Array.from({ length: count }, (_, i) => {
      const dx = Math.round((Math.random() - 0.5) * 600)
      const dy = Math.round(200 + Math.random() * 300)
      const rot = Math.round((Math.random() - 0.5) * 1440)
      const delay = Math.round(Math.random() * 200)
      const color = palette[i % palette.length]
      return { key: i, dx, dy, rot, delay, color }
    })
  })

  return (
    <div
      aria-hidden="true"
      className="pointer-events-none absolute inset-0 overflow-visible"
    >
      {pieces.map((p) => (
        <span
          key={p.key}
          className="confetti-piece"
          style={
            {
              backgroundColor: p.color,
              animationDelay: `${p.delay}ms`,
              // CSS custom props consumed by the confetti-burst keyframe
              ['--dx' as string]: `${p.dx}px`,
              ['--dy' as string]: `${p.dy}px`,
              ['--rot' as string]: `${p.rot}deg`,
            } as React.CSSProperties
          }
        />
      ))}
    </div>
  )
}
