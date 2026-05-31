import * as React from "react"

/**
 * True when the primary pointer is coarse (touchscreens). Used to pick a native
 * `<input type="date">` (great mobile picker) over a custom formatted text field
 * on desktop. Mirrors the matchMedia pattern in {@link useIsMobile}.
 */
export function useIsTouchDevice() {
  const [isTouch, setIsTouch] = React.useState<boolean>(() => {
    if (typeof window === "undefined" || !window.matchMedia) return false
    return window.matchMedia("(pointer: coarse)").matches
  })

  React.useEffect(() => {
    const mql = window.matchMedia("(pointer: coarse)")
    const onChange = () => setIsTouch(mql.matches)
    mql.addEventListener("change", onChange)
    setIsTouch(mql.matches)
    return () => mql.removeEventListener("change", onChange)
  }, [])

  return isTouch
}
