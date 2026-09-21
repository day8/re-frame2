import React from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider, queryOptions, useQuery, useMutation } from '@tanstack/react-query'
import { ReactQueryDevtools } from '@tanstack/react-query-devtools'

type Article = { slug: string; title: string; favorited: boolean; favoritesCount: number }
const article: Article = { slug: 'hello-conduit', title: 'Hello Conduit', favorited: false, favoritesCount: 0 }
let server = structuredClone(article)
let auto = true
let brokenInvalidation = false
let viewer = 'Alice'
let sequence = 0
const ledger: any[] = []
const pending = new Map<number, { resolve: (v: any) => void; reject: (e: Error) => void; value: any }>()
function request(kind: string, value?: boolean) {
  const id = ++sequence
  // This controlled backend commits a write when requested; delivery is a separate step.
  if (kind === 'write') server = { ...server, favorited: !!value, favoritesCount: value ? 1 : 0 }
  const result = kind === 'favorites' ? { articles: server.favorited ? [structuredClone(server)] : [], articlesCount: server.favorited ? 1 : 0 }
    : kind === 'list' ? { articles: [structuredClone(server)], articlesCount: 1 } : { article: structuredClone(server) }
  ledger.push({ id, kind, viewer, commitOrder: id, delivered: false })
  return new Promise<any>((resolve, reject) => {
    pending.set(id, { resolve, reject, value: result })
    if (auto) queueMicrotask(() => deliver(id))
  })
}
function deliver(id: number, fail = false) {
  const p = pending.get(id)
  if (!p) throw new Error(`No pending request ${id}`)
  pending.delete(id)
  Object.assign(ledger.find(r => r.id === id), { delivered: true, failed: fail })
  fail ? p.reject(new Error('Controlled response failure')) : p.resolve(p.value)
}
const client = new QueryClient({ defaultOptions: { queries: { staleTime: 60_000, gcTime: 300_000, retry: false, refetchOnWindowFocus: false, refetchOnReconnect: false } } })
const options = (kind: string, who = viewer) => queryOptions({ queryKey: ['viewer', who, kind, article.slug], queryFn: () => request(kind) })
const patch = (old: any, yes: boolean) => old?.article ? { ...old, article: { ...old.article, favorited: yes, favoritesCount: yes ? 1 : 0 } }
  : old?.articles ? { ...old, articles: old.articles.map((a: Article) => ({ ...a, favorited: yes, favoritesCount: yes ? 1 : 0 })) } : old
function Detail({ duplicate = false }: { duplicate?: boolean }) {
  const q = useQuery(options('detail'))
  return <section data-testid={duplicate ? 'detail-copy' : 'detail'}>{q.data?.article.title ?? 'Loading'}: {String(q.data?.article.favorited)} ({q.data?.article.favoritesCount}) {q.isFetching && 'Refreshing'} {q.isError && 'Refresh failed'}</section>
}
function Listing({ kind }: { kind: string }) {
  const q = useQuery(options(kind))
  return <section data-testid={kind}>{kind}: {q.data?.articles.map((a: Article) => `${a.slug}:${a.favorited}:${a.favoritesCount}`).join(',')} count={q.data?.articlesCount} {q.isError && 'Refresh failed'}</section>
}
function App() {
  const mutation = useMutation({
    mutationKey: ['favorite', viewer, article.slug],
    mutationFn: (yes: boolean) => request('write', yes),
    onMutate: async yes => {
      await client.cancelQueries({ queryKey: ['viewer', viewer] })
      client.setQueriesData({ queryKey: ['viewer', viewer] }, old => patch(old, yes))
    },
    onSuccess: data => client.setQueryData(options('detail').queryKey, data),
    // The official optimistic guide permits reconciliation by refetch; no naive snapshot rollback.
    onSettled: () => brokenInvalidation ? undefined : client.invalidateQueries({ queryKey: ['viewer', viewer] }),
  })
  return <><h1>Conduit server-state slice</h1><Detail/><Detail duplicate/><Listing kind="list"/><Listing kind="favorites"/>
    <button onClick={() => mutation.mutate(true)}>Favorite</button><button onClick={() => mutation.mutate(false)}>Unfavorite</button>
    <p data-testid="mutation">{mutation.status}</p><ReactQueryDevtools initialIsOpen /></>
}
Object.assign(window, { research: { client, options, ledger, pending, deliver,
  setAuto: (yes: boolean) => { auto = yes },
  setBroken: (yes: boolean) => { brokenInvalidation = yes },
  getServer: () => structuredClone(server),
  setViewer: (who: string) => { viewer = who },
  refresh: () => client.invalidateQueries({ queryKey: ['viewer', viewer] }),
  version: '5.102.8' } })
createRoot(document.getElementById('app')!).render(<QueryClientProvider client={client}><App/></QueryClientProvider>)
