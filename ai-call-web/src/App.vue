<template>
  <router-view />
</template>

<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
html, body, #app {
  height: 100%;
}
body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
  background: #f0f2f5;
}
html.h5-standalone,
body.h5-standalone {
  height: 100%;
  overflow: hidden;
  background: #f0f2f5;
}
body.h5-standalone #app {
  height: 100%;
  overflow: hidden;
}
</style>
