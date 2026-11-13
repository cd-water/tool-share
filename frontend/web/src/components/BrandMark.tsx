import { useId } from 'react'

type BrandMarkProps = {
  size?: number
}

/**
 * 「互扣六角 Shared Hex」品牌标识（原型包 brand_svg() 一比一）。
 * 六边形取自螺母 / 工具，上下两块以 S 形拼合缝交接隐喻共享，中心圆点为连接节点。
 */
export function BrandMark({ size = 34 }: BrandMarkProps) {
  const gradientId = `bm-copper-${useId().replace(/:/g, '')}`
  return (
    <svg width={size} height={size} viewBox="0 0 64 64" aria-hidden="true" className="shrink-0">
      <defs>
        <linearGradient id={gradientId} x1="8" y1="4" x2="56" y2="44" gradientUnits="userSpaceOnUse">
          <stop stopColor="#F2C672" />
          <stop offset=".55" stopColor="#D89B43" />
          <stop offset="1" stopColor="#A96A12" />
        </linearGradient>
      </defs>
      <path
        d="M32 3.2 57.4 17.8v28.4L32 60.8 6.6 46.2V17.8Z"
        fill="#26302F"
        stroke="#FFF9EC"
        strokeOpacity=".52"
        strokeWidth="1.2"
        strokeLinejoin="round"
      />
      <path d="M32 3.2 57.4 17.8v5.6C44 23.4 44 42 32 42S20 23.4 6.6 23.4v-5.6Z" fill={`url(#${gradientId})`} />
      <path
        d="M6.6 23.4C20 23.4 20 42 32 42s12-18.6 25.4-18.6"
        fill="none"
        stroke="#FFF9EC"
        strokeWidth="2.4"
        strokeLinecap="round"
      />
      <circle cx="32" cy="42" r="3.1" fill="#FFF9EC" />
    </svg>
  )
}
