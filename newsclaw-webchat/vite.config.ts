import { defineConfig } from 'vite'
import { resolve } from 'path'

export default defineConfig({
  build: {
    lib: {
      entry: resolve(__dirname, 'src/index.ts'),
      name: 'NewsClawWebChat',
      formats: ['es', 'umd'],
      fileName: (format) => `newsclaw-webchat.${format}.js`,
    },
    rollupOptions: {
      output: {
        assetFileNames: 'newsclaw-webchat.[ext]',
      },
    },
    cssCodeSplit: false,
    minify: 'esbuild',
  },
})
