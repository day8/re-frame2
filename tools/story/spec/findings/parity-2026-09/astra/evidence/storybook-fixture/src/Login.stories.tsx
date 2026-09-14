import type { Meta, StoryObj } from '@storybook/react-vite';
import { expect, fn } from 'storybook/test';
import { Login } from './Login';

const meta = {
  title:'Research/Login', component:Login, tags:['autodocs'],
  args:{heading:'Sign in',authenticate:fn(async(email:string,password:string)=>{
    await new Promise(resolve=>setTimeout(resolve,30));
    if(password!=='correct-horse')throw new Error('Invalid credentials.');
    return {email};
  })},
} satisfies Meta<typeof Login>;
export default meta;
type Story=StoryObj<typeof meta>;
export const Idle:Story={};
export const RetryToSuccess:Story={
  play:async({canvas,userEvent,step,args})=>{
    await step('A rejected login shows the error',async()=>{
      await userEvent.type(canvas.getByLabelText('Email'),'ada@example.com');
      await userEvent.type(canvas.getByLabelText('Password'),'wrong');
      await userEvent.click(canvas.getByRole('button',{name:'Sign in'}));
      await expect(await canvas.findByRole('alert')).toHaveTextContent('Invalid credentials.');
    });
    await step('Retry with corrected credentials succeeds',async()=>{
      await userEvent.clear(canvas.getByLabelText('Password'));
      await userEvent.type(canvas.getByLabelText('Password'),'correct-horse');
      await userEvent.click(canvas.getByRole('button',{name:'Retry'}));
      await expect(await canvas.findByText('Welcome, ada@example.com')).toBeVisible();
      await expect(args.authenticate).toHaveBeenCalledTimes(2);
    });
  },
};
export const DeliberateFailure:Story={
  play:async({canvas,userEvent,step})=>{
    await step('Detect the wrong expected welcome user',async()=>{
      await userEvent.type(canvas.getByLabelText('Email'),'ada@example.com');
      await userEvent.type(canvas.getByLabelText('Password'),'correct-horse');
      await userEvent.click(canvas.getByRole('button',{name:'Sign in'}));
      await expect(await canvas.findByText('Welcome, ada@example.com')).toHaveTextContent('Welcome, grace@example.com');
    });
  },
};
