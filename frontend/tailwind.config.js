/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  theme: {
    extend: {
      colors: {
        canvas: '#F3F7F9',
        surface: '#FFFFFF',
        ink: {
          DEFAULT: '#0F2E42',
          soft: '#5B7486',
          mute: '#8AA1B4',
        },
        line: '#E4ECF2',
        brand: {
          DEFAULT: '#0FA295',
          hover: '#0B877C',
          deep: '#0A6F66',
          soft: '#E0F4F0',
          pale: '#EFFAF7',
        },
        sand: '#E8A13B',
        warn: '#B4792A',
        danger: '#E25A4E',
        info: '#4C8DF6',
      },
      fontFamily: {
        sans: [
          '-apple-system', 'BlinkMacSystemFont', 'Segoe UI', 'PingFang SC',
          'Hiragino Sans GB', 'Microsoft YaHei', 'Noto Sans SC', 'Helvetica Neue', 'sans-serif',
        ],
        display: [
          'Manrope', 'Sora', '-apple-system', 'BlinkMacSystemFont', 'Segoe UI',
          'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', 'sans-serif',
        ],
      },
      boxShadow: {
        card: '0 1px 2px rgba(15,46,66,.04), 0 10px 28px -8px rgba(15,46,66,.09)',
        lift: '0 6px 18px -6px rgba(15,46,66,.16), 0 18px 44px -16px rgba(11,132,124,.22)',
        inset: 'inset 0 2px 4px rgba(15,46,66,.05)',
      },
      borderRadius: {
        xl2: '1.15rem',
        xl3: '1.5rem',
      },
      keyframes: {
        rise: {
          '0%': { opacity: '0', transform: 'translateY(10px)' },
          '100%': { opacity: '1', transform: 'translateY(0)' },
        },
        floaty: {
          '0%,100%': { transform: 'translate3d(0,0,0) scale(1)' },
          '50%': { transform: 'translate3d(0,-18px,0) scale(1.04)' },
        },
        pulseDot: {
          '0%,80%,100%': { opacity: '.25', transform: 'scale(.8)' },
          '40%': { opacity: '1', transform: 'scale(1)' },
        },
        shimmer: {
          '0%': { backgroundPosition: '-160% 0' },
          '100%': { backgroundPosition: '160% 0' },
        },
      },
    },
  },
  plugins: [],
}