type BrandMarkProps = {
  size?: number
}

export function BrandMark({ size = 34 }: BrandMarkProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 64 64"
      aria-hidden="true"
      className="shrink-0"
    >
      <rect width="64" height="64" rx="16" fill="#F5B301" />
      <circle cx="32" cy="32" r="23" fill="#FFFFFF" />
      <circle cx="23.5" cy="27" r="3.4" fill="#F5B301" />
      <circle cx="40.5" cy="27" r="3.4" fill="#F5B301" />
      <path
        d="M21.5 36.5C24.5 41 27.9 43.2 32 43.2C36.1 43.2 39.5 41 42.5 36.5"
        stroke="#F5B301"
        strokeWidth="4"
        strokeLinecap="round"
        fill="none"
      />
    </svg>
  )
}
