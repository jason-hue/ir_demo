# Frontend demo

This frontend is the classroom demo UI for the backend search and chat endpoints.

## Local runtime defaults

The frontend defaults to the production-safe backend entrypoint at `http://127.0.0.1:8080`.

If you want the classroom demo backend instead, opt into the `demo` profile explicitly:

```bash
/home/knifefire/.local/opt/apache-maven-3.9.9/bin/mvn spring-boot:run -Dspring-boot.run.profiles=demo
```

That demo backend runs on `http://127.0.0.1:18082` with seeded Lucene data and no live crawler dependency:

```bash
/home/knifefire/.local/opt/apache-maven-3.9.9/bin/mvn spring-boot:run -Dspring-boot.run.profiles=demo
```

The UI reads its backend base URL from `VITE_API_BASE_URL` and falls back to `http://127.0.0.1:8080`.

Copy the example env file if you want to point the UI somewhere else:

```bash
cp .env.example .env
```

## Commands

```bash
npm install
npm run dev
npm run build
```

## Classroom-safe flow

1. Optionally start local Qdrant with `sg docker -c 'docker compose -f docker-compose.local.yml up -d qdrant'`.
2. Optionally start Ollama locally if you want answer generation and vector seeding.
3. Start the backend with the `demo` profile shown above.
4. Start the frontend with `npm run dev`.
5. Test keyword, hybrid, and chat flows from the seeded Tencent fixture set.

If Ollama or Qdrant are unavailable, the backend stays runnable and returns the existing machine-checkable degraded responses.
