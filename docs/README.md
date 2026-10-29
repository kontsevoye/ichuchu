# ichuchu docs site

Static landing page for [ichuchu](https://github.com/kontsevoye/ichuchu), built with
Nuxt 3 + Tailwind. Styled to match the in-app terminal aesthetic (Tokyo Night
palette, JetBrains Mono, terminal-block accents).

## Develop

```sh
nix develop .#frontend
cd docs
pnpm install --frozen-lockfile
pnpm run dev      # local dev server
```

## Build static site

```sh
pnpm run generate
# output in .output/public — deploy that to any static host
```

If you deploy under a subpath (e.g. GitHub Pages at `/ichuchu/`):

```sh
NUXT_APP_BASE_URL=/ichuchu/ pnpm run generate
```

## Layout

- `app.vue` — single-page landing
- `assets/css/main.css` — global styles + custom utilities (`.term-block`, `.chip`, `.btn`)
- `tailwind.config.js` — palette + fonts
- `public/` — site logo

## Automatic and manual deployment

The **Deploy frontend** workflow publishes `docs/.output/public` to `gh-pages`
for pushes to `main` that change site files under `docs/`. Top-level technical
Markdown instructions (`docs/*.md`) are excluded. Changes to the deployment
workflow or its pinned Nix inputs (`flake.nix`, `flake.lock`, `devshell.nix`) also
trigger a rebuild. Android/native-only changes do not trigger frontend deployment.

To rebuild and deploy manually, open **Actions → Deploy frontend → Run workflow**
and select `main` (or the intended source branch). The `workflow_dispatch` trigger
is independent of the path filter. A new deployment supersedes an older running
deployment. Installation uses the frozen pnpm lockfile and a cached pnpm store
inside the lightweight `frontend` Nix shell.
