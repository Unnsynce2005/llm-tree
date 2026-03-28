import { useRef, useCallback, useEffect, useState } from 'react';
import type { StreamChunk, SendMessagePayload } from '../types';

type Status = 'disconnected' | 'connecting' | 'connected';

export function useWebSocket(onChunk: (chunk: StreamChunk) => void) {
  const wsRef = useRef<WebSocket | null>(null);
  const [status, setStatus] = useState<Status>('disconnected');
  const onChunkRef = useRef(onChunk);
  onChunkRef.current = onChunk;

  const connect = useCallback(() => {
    if (wsRef.current?.readyState === WebSocket.OPEN) return;

    setStatus('connecting');

    let wsUrl: string;
    const apiUrl = import.meta.env.VITE_API_URL;
    if (apiUrl) {
      // Production: derive WS URL from API URL
      const url = new URL(apiUrl);
      const wsProtocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
      wsUrl = `${wsProtocol}//${url.host}/ws/chat`;
    } else {
      // Dev: connect through Vite proxy
      const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
      wsUrl = `${protocol}//${window.location.host}/ws/chat`;
    }

    const ws = new WebSocket(wsUrl);

    ws.onopen = () => setStatus('connected');
    ws.onclose = () => {
      setStatus('disconnected');
      setTimeout(() => connect(), 2000);
    };
    ws.onerror = () => ws.close();
    ws.onmessage = (event) => {
      try {
        const chunk: StreamChunk = JSON.parse(event.data);
        onChunkRef.current(chunk);
      } catch (e) {
        console.error('Failed to parse WS message:', e);
      }
    };

    wsRef.current = ws;
  }, []);

  const sendMessage = useCallback((payload: SendMessagePayload) => {
    if (wsRef.current?.readyState !== WebSocket.OPEN) {
      console.error('WebSocket not connected');
      return;
    }
    wsRef.current.send(JSON.stringify(payload));
  }, []);

  const disconnect = useCallback(() => {
    wsRef.current?.close();
    wsRef.current = null;
  }, []);

  useEffect(() => {
    connect();
    return () => disconnect();
  }, [connect, disconnect]);

  return { status, sendMessage, connect };
}
