import React, { useState } from 'react';

export type LoginProps = {
  heading: string;
  authenticate: (email: string, password: string) => Promise<{email:string}>;
};

export function Login({heading, authenticate}: LoginProps) {
  const [state,setState]=useState<'idle'|'submitting'|'error'|'authenticated'>('idle');
  const [email,setEmail]=useState('');
  const [password,setPassword]=useState('');
  const [error,setError]=useState('');
  const [user,setUser]=useState('');
  async function submit(event:React.FormEvent) {
    event.preventDefault();setState('submitting');setError('');
    try {const result=await authenticate(email,password);setUser(result.email);setState('authenticated');}
    catch(e) {setError((e as Error).message);setState('error');}
  }
  return <main style={{fontFamily:'system-ui',maxWidth:380,padding:24}}>
    <h1>{heading}</h1>
    <p role="status">State: {state}</p>
    {state==='authenticated'
      ? <><p>Welcome, {user}</p><button onClick={()=>setState('idle')}>Sign out</button></>
      : <form onSubmit={submit}>
          <p><label>Email <input type="email" value={email} onChange={e=>setEmail(e.target.value)} disabled={state==='submitting'} /></label></p>
          <p><label>Password <input type="password" value={password} onChange={e=>setPassword(e.target.value)} disabled={state==='submitting'} /></label></p>
          <button disabled={state==='submitting'}>{state==='submitting'?'Signing in…':state==='error'?'Retry':'Sign in'}</button>
          {error && <p role="alert">{error}</p>}
        </form>}
  </main>;
}
