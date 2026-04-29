<script setup>
import { ref } from 'vue'

const apiBaseUrl = (import.meta.env.VITE_API_BASE_URL || 'http://127.0.0.1:8080').replace(/\/$/, '')
const CHAT_REQUEST_TIMEOUT_MS = 130000
const question = ref('')
const loading = ref(false)
const errorMsg = ref('')
const answer = ref('')

const displayAnswer = (text) => {
  if (!text) return ''
  return text.replace(/\[(\d+)\]/g, '').replace(/\s{2,}/g, ' ').trim()
}

const submitQuestion = async () => {
  if (!question.value.trim()) return

  let requestTimeoutId = null

  loading.value = true
  errorMsg.value = ''
  answer.value = ''

  try {
    const abortController = new AbortController()
    const url = `${apiBaseUrl}/chat/ask`
    requestTimeoutId = window.setTimeout(() => {
      abortController.abort()
    }, CHAT_REQUEST_TIMEOUT_MS)
    const options = {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ question: question.value }),
      signal: abortController.signal
    }

    const response = await fetch(url, options)
    const data = await response.json()

    if (!data.success) {
      if (data.data) {
        errorMsg.value = data.msg || 'Degraded mode'
        answer.value = data.data.answer || ''
      } else {
        errorMsg.value = data.msg || 'An error occurred'
      }
    } else {
      answer.value = data.data.answer || ''
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

    <div class="search-box">
      <textarea 
        v-model="question" 
        data-testid="question-input" 
        placeholder="Ask a question..."
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

    <div v-if="answer || loading" data-testid="answer-panel" class="answer-panel">
      <h3 v-if="loading">Generating answer...</h3>
      <div v-else>
        <h3>Answer</h3>
        <p>{{ displayAnswer(answer) || 'No answer available.' }}</p>
      </div>
    </div>

    <div v-else-if="!loading && !errorMsg && question && answer === ''" class="empty-state">
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

.answer-panel {
  background-color: #f8f9fa;
  padding: 20px;
  border-radius: 4px;
  margin-bottom: 20px;
  border-left: 4px solid #007bff;
}

.empty-state {
  color: #666;
  font-style: italic;
}
</style>
