export interface FooterProps {
  children?: React.ReactNode
}

export function Footer({ children }: FooterProps) {
  return children ? <footer className="app-footer fs-7">{children}</footer> : null
}
