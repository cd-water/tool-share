/**
 * 文件职责：ToolShare 品牌标识（BrandMark）SVG 组件。
 *
 * 图形语言：“互扣六角 / Shared Hex”——外轮廓取六角螺母，内部用上下两块
 * 拼合的 S 形缝隙表达“共享、互换”，中心圆点代表连接节点。整体使用黄铜
 * 渐变，呼应“社区工具房 / 工坊”的温暖质感。
 *
 * 组件只负责绘制，不包含文字（文字由使用方在旁侧渲染），也不处理点击。
 * 尺寸通过 size 控制，颜色由 SVG 内的渐变与描边决定；如需适配深浅主题，
 * 应在设计层扩展，而不是在业务页面覆盖 path。
 */
import { useId } from 'react'

/**
 * BrandMark 的入参。
 *
 * @property size 标识边长（像素），默认 34；保持正方形，内部 viewBox 为 64×64。
 */
type BrandMarkProps = {
  size?: number
}

/**
 * 品牌六角标识。
 *
 * @param props.size 边长，默认 34；建议使用 24–48 之间的值，过小会丢失缝隙细节。
 * @returns 一个 aria-hidden 的装饰性 SVG；品牌名称由相邻文本提供。
 *
 * 副作用：调用 useId 生成唯一的渐变 id，避免同一页面多次渲染时 id 冲突。
 * 由于该 id 依赖 React 的 useId，服务端渲染与客户端水合结果一致。
 */
/**
 * 「互扣六角 Shared Hex」品牌标识（原型包 brand_svg() 一比一）。
 * 六边形取自螺母 / 工具，上下两块以 S 形拼合缝交接隐喻共享，中心圆点为连接节点。
 */
export function BrandMark({ size = 34 }: BrandMarkProps) {
  // useId 自带冒号，直接放入 SVG id 在 CSS 选择器中需要转义，
  // 因此这里统一去掉冒号，得到 bm-copper-xxxx 形式的安全 id。
  // 渐变按实例生成，保证多个 BrandMark 同时出现时各自独立、互不串色。
  const gradientId = `bm-copper-${useId().replace(/:/g, '')}`
  return (
    // 根 SVG 固定 viewBox 64×64，宽高由 size 控制，因此任意尺寸都等比缩放。
    // aria-hidden 表示该图形是装饰性的，读屏应跳过；品牌语义由外部文本承担。
    // shrink-0 防止在 flex 布局中被压缩变形。
    <svg width={size} height={size} viewBox="0 0 64 64" aria-hidden="true" className="shrink-0">
      {/*
       * defs 中定义黄铜渐变：从亮金到铜棕，模拟金属受光。
       * 渐变坐标使用 userSpaceOnUse，保证不同尺寸下光感位置一致。
       * 若新增主题色，应复制一份渐变而不是复用 id，避免跨实例污染。
       */}
      <defs>
        <linearGradient id={gradientId} x1="8" y1="4" x2="56" y2="44" gradientUnits="userSpaceOnUse">
          <stop stopColor="#F2C672" />
          <stop offset=".55" stopColor="#D89B43" />
          <stop offset="1" stopColor="#A96A12" />
        </linearGradient>
      </defs>
      {/*
       * 外层六边形：深色底 + 浅色细描边，形成“金属铭牌”的轮廓。
       * strokeOpacity 控制在半透明范围，避免描边喧宾夺主。
       */}
      <path
        d="M32 3.2 57.4 17.8v28.4L32 60.8 6.6 46.2V17.8Z"
        fill="#26302F"
        stroke="#FFF9EC"
        strokeOpacity=".52"
        strokeWidth="1.2"
        strokeLinejoin="round"
      />
      {/*
       * 上半部分填充黄铜渐变，与下半部分形成材质对比；
       * 渐变通过 url(#id) 引用上面生成的唯一标识。
       */}
      <path d="M32 3.2 57.4 17.8v5.6C44 23.4 44 42 32 42S20 23.4 6.6 23.4v-5.6Z" fill={`url(#${gradientId})`} />
      {/*
       * S 形拼合缝：用一条浅色曲线强调“两块互扣”的结构，
       * 线宽 2.4、圆头端点，让缝隙在缩小后仍然可见。
       */}
      <path
        d="M6.6 23.4C20 23.4 20 42 32 42s12-18.6 25.4-18.6"
        fill="none"
        stroke="#FFF9EC"
        strokeWidth="2.4"
        strokeLinecap="round"
      />
      {/*
       * 中心圆点是“连接节点”，使用与描边相同的浅色，强化整体识别度。
       * 至此图形结束；不添加阴影或滤镜，保持 SVG 轻量、可无限缩放。
       */}
      <circle cx="32" cy="42" r="3.1" fill="#FFF9EC" />
    </svg>
  )
// 文件结束：BrandMark 是纯展示组件，无状态、无副作用（useId 由 React 管理）。
// 使用方通常把它放在品牌文字左侧，并用 flex + gap 控制间距。
// 不要在其他组件中复制这段 path，统一引用本组件以保持品牌一致。
}
