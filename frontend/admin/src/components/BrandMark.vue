<script setup lang="ts">
/**
 * 管理端品牌标记：铜金渐变的六边形工坊徽标。
 *
 * <p>SVG 内联绘制，不依赖外部图片；渐变 id 使用 useId 生成，
 * 避免同一页面多次使用组件时 id 冲突。颜色固定为品牌铜金，
 * 不随主题变量变化，保证深色侧栏上仍有足够对比度。</p>
 *
 * <p>使用范围：管理端侧栏、登录页品牌区与页脚。尺寸由调用方传入，
 * 组件自身不读取全局状态，也不发起请求，属于纯展示组件。</p>
 *
 * <p>可访问性：图形本身标记 aria-hidden，品牌名称由相邻文本提供，
 * 避免读屏器把装饰图形当成独立信息重复朗读。</p>
 */
// useId 用于生成 SVG 渐变 id，保证同一页面多次实例化时互不冲突。
import { useId } from 'vue'

// 组件唯一入参：渲染尺寸，默认 34px；宽高保持一致，避免徽标变形。
// 尺寸只影响 SVG 的 width/height，viewBox 固定为 64×64，缩放不改变比例。
withDefaults(defineProps<{ size?: number }>(), { size: 34 })

// 生成唯一渐变 id；useId 可能包含冒号，SVG id 中需要去掉。
const gradientId = `bm-copper-${useId().replace(/[:]/g, '')}`
</script>

<template>
  <!-- 装饰性品牌图形：aria-hidden 避免屏幕阅读器重复朗读。 -->
  <!-- 宽高由 size 控制，viewBox 固定 64×64，任意尺寸下保持同一比例。 -->
  <svg class="brand-mark" :width="size" :height="size" viewBox="0 0 64 64" aria-hidden="true">
    <defs>
      <!-- 铜金渐变：亮铜、主体铜、深铜三段，用于徽标上半部与内层弧面。 -->
      <!-- 渐变方向从左下到右上，模拟铜件受光面。 -->
      <linearGradient :id="gradientId" x1="8" y1="4" x2="56" y2="44" gradientUnits="userSpaceOnUse">
        <!-- 亮铜：高光起点。 -->
        <stop stop-color="#F2C672" />
        <!-- 主体铜：品牌主色。 -->
        <stop offset=".55" stop-color="#D89B43" />
        <!-- 深铜：阴影收口。 -->
        <stop offset="1" stop-color="#A96A12" />
      </linearGradient>
    </defs>
    <!-- 外层六边形：深石墨底 + 浅色描边，形成工具柜金属框的视觉。 -->
    <!-- 描边宽度 1.2、圆角连接，保证小尺寸下轮廓仍然清晰。 -->
    <!-- 该层不使用渐变，作为铜色弧面的深色底衬。 -->
    <path
      d="M32 3.2 57.4 17.8v28.4L32 60.8 6.6 46.2V17.8Z"
      fill="#26302F"
      stroke="#FFF9EC"
      stroke-opacity=".52"
      stroke-width="1.2"
      stroke-linejoin="round"
    />
    <!-- 上半部铜色弧面：使用上面的渐变填充，模拟工坊铜件的高光。 -->
    <!-- 渐变 id 通过 url(#id) 引用，id 由 useId 动态生成。 -->
    <!-- 该路径不描边，完全依赖渐变形成层次，减少小尺寸下的线条噪声。 -->
    <path d="M32 3.2 57.4 17.8v5.6C44 23.4 44 42 32 42S20 23.4 6.6 23.4v-5.6Z" :fill="`url(#${gradientId})`" />
    <!-- 内层浅色弧线：勾勒工具柜抽屉/把手轮廓。 -->
    <!-- 使用 round 端点让弧线在两端自然收圆，避免硬切角。 -->
    <!-- 线宽 2.4 在缩放后仍保持可见，不被深色底吞没。 -->
    <path
      d="M6.6 23.4C20 23.4 20 42 32 42s12-18.6 25.4-18.6"
      fill="none"
      stroke="#FFF9EC"
      stroke-width="2.4"
      stroke-linecap="round"
    />
    <!-- 底部圆点：品牌标记的视觉锚点。 -->
    <!-- 半径 3.1 在 34px 渲染尺寸下约 1.6px，保持精致而非突兀。 -->
    <!-- 颜色使用浅米白，与描边、弧线保持同一层级。 -->
    <circle cx="32" cy="42" r="3.1" fill="#FFF9EC" />
  </svg>
</template>

<style scoped>
/* 徽标按块级元素渲染，并在 flex 容器中禁止被压缩。 */
/* 不设置固定宽高，尺寸由 SVG 的 width/height 属性决定。 */
/* 仅影响布局，不参与主题色切换，铜金配色固定在模板内。 */
.brand-mark {
  display: block;
  flex-shrink: 0;
}
</style>
