<script setup>
import { ref } from 'vue'

const apiBaseUrl = (import.meta.env.VITE_API_BASE_URL || 'http://127.0.0.1:8080').replace(/\/$/, '')
const CHAT_REQUEST_TIMEOUT_MS = 130000
const endpoint = ref('chat') // 'kw', 'hybrid', 'chat'
const question = ref('')
const loading = ref(false)
const errorMsg = ref('')
const answer = ref('')
const retrievalMode = ref('')
const results = ref([])
const isDegraded = ref(false)

const submitQuestion = async () => {
  if (!question.value.trim()) return

  let requestTimeoutId = null

  loading.value = true
  errorMsg.value = ''
  answer.value = ''
  retrievalMode.value = ''
  results.value = []
  isDegraded.value = false

  try {
    let url = ''
    let options = {}
    const abortController = endpoint.value === 'chat' ? new AbortController() : null

    if (endpoint.value === 'kw') {
      url = `${apiBaseUrl}/query/kw?kw=${encodeURIComponent(question.value)}`
      options = { method: 'GET' }
    } else if (endpoint.value === 'hybrid') {
      url = `${apiBaseUrl}/query/hybrid`
      options = {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question: question.value })
      }
    } else {
      url = `${apiBaseUrl}/chat/ask`
      requestTimeoutId = window.setTimeout(() => {
        abortController.abort()
      }, CHAT_REQUEST_TIMEOUT_MS)
      options = {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question: question.value }),
        signal: abortController.signal
      }
    }

    const response = await fetch(url, options)
    const data = await response.json()

    if (!data.success) {
      if (data.data) {
        isDegraded.value = true
        errorMsg.value = data.msg || 'Degraded mode'
        if (endpoint.value === 'chat') {
          answer.value = data.data.answer || ''
          retrievalMode.value = data.data.retrievalMode || ''
          results.value = data.data.citations || []
        } else if (endpoint.value === 'hybrid') {
          retrievalMode.value = data.data.mode || ''
          results.value = data.data.results || []
        }
      } else {
        errorMsg.value = data.msg || 'An error occurred'
      }
    } else {
      if (endpoint.value === 'kw') {
        results.value = data.data || []
      } else if (endpoint.value === 'hybrid') {
        retrievalMode.value = data.data.mode || ''
        results.value = data.data.results || []
      } else {
        answer.value = data.data.answer || ''
        retrievalMode.value = data.data.retrievalMode || ''
        results.value = data.data.citations || []
      }
    }
  } catch (err) {
    if (err?.name === 'AbortError') {
      errorMsg.value = `Chat request timed out after ${Math.round(CHAT_REQUEST_TIMEOUT_MS / 1000)}s. The AI path may be stalled.`
    } else {
      errorMsg.value = 'Failed to connect to the server'
    }
  } finally {
    if (requestTimeoutId !== null) {
      window.clearTimeout(requestTimeoutId)
    }
    loading.value = false
  }
}
</script>

<template>
  <div class="container">
    <h1>IR Demo RAG</h1>
    <p class="api-hint">API: {{ apiBaseUrl }}</p>
    
    <div class="endpoint-selector">
      <label>
        <input type="radio" value="kw" v-model="endpoint"> Keyword Search
      </label>
      <label>
        <input type="radio" value="hybrid" v-model="endpoint"> Hybrid Search
      </label>
      <label>
        <input type="radio" value="chat" v-model="endpoint"> Chat
      </label>
    </div>

    <div class="search-box">
      <textarea 
        v-model="question" 
        data-testid="question-input" 
        placeholder="Ask a question or enter keywords..."
        rows="3"
      ></textarea>
      <button 
        @click="submitQuestion" 
        data-testid="submit-question"
        :disabled="loading"
      >
        {{ loading ? 'Searching...' : 'Submit' }}
      </button>
    </div>

    <div v-if="errorMsg" data-testid="error-banner" class="error-banner">
      {{ errorMsg }}
    </div>

    <div v-if="retrievalMode" data-testid="retrieval-mode-badge" class="badge">
      Mode: {{ retrievalMode }}
    </div>

    <div v-if="(answer || loading) && endpoint === 'chat'" data-testid="answer-panel" class="answer-panel">
      <h3 v-if="loading">Generating answer...</h3>
      <div v-else>
        <h3>Answer</h3>
        <p>{{ answer || 'No answer available.' }}</p>
      </div>
    </div>

    <div v-if="results.length > 0" data-testid="results-list" class="results-list">
      <h3>{{ endpoint === 'chat' ? 'Sources' : 'Results' }}</h3>
      <div v-for="(result, index) in results" :key="index" class="result-card">
        <h4>{{ result.title }}</h4>
        <div class="meta">
          <span v-if="result.source">Source: {{ result.source }}</span>
          <span v-if="result.author">Author: {{ result.author }}</span>
          <span v-if="result.publishTime || result.time">Time: {{ result.publishTime || result.time }}</span>
        </div>
        <p v-if="result.chunkText" class="chunk-text">{{ result.chunkText }}</p>
        <a v-if="result.sourceUrl" :href="result.sourceUrl" target="_blank">Read more</a>
      </div>
    </div>
    
    <div v-else-if="!loading && !errorMsg && question && answer === '' && results.length === 0" class="empty-state">
      No results found.
    </div>
  </div>
</template>

<style scoped>
.container {
  max-width: 800px;
  margin: 0 auto;
  padding: 20px;
  font-family: sans-serif;
}

.api-hint {
  margin: 0 0 16px;
  color: #666;
  font-size: 14px;
}

.endpoint-selector {
  display: flex;
  gap: 20px;
  margin-bottom: 20px;
}

.search-box {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-bottom: 20px;
}

textarea {
  padding: 10px;
  font-size: 16px;
  border: 1px solid #ccc;
  border-radius: 4px;
  resize: vertical;
}

button {
  padding: 10px 20px;
  font-size: 16px;
  background-color: #007bff;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  align-self: flex-start;
}

button:disabled {
  background-color: #ccc;
}

.error-banner {
  background-color: #f8d7da;
  color: #721c24;
  padding: 10px;
  border-radius: 4px;
  margin-bottom: 20px;
}

.badge {
  display: inline-block;
  background-color: #e2e3e5;
  color: #383d41;
  padding: 5px 10px;
  border-radius: 4px;
  font-size: 14px;
  margin-bottom: 20px;
}

.answer-panel {
  background-color: #f8f9fa;
  padding: 20px;
  border-radius: 4px;
  margin-bottom: 20px;
  border-left: 4px solid #007bff;
}

.results-list {
  display: flex;
  flex-direction: column;
  gap: 15px;
}

.result-card {
  border: 1px solid #ddd;
  padding: 15px;
  border-radius: 4px;
}

.result-card h4 {
  margin-top: 0;
  margin-bottom: 10px;
}

.meta {
  font-size: 12px;
  color: #666;
  margin-bottom: 10px;
  display: flex;
  gap: 15px;
}

.chunk-text {
  font-size: 14px;
  line-height: 1.5;
  color: #333;
}

.empty-state {
  color: #666;
  font-style: italic;
}
</style>
