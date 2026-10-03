package com.duplex.mobile.agent

import com.google.gson.JsonArray
import com.google.gson.JsonParser

/**
 * 内置 agent 的工具定义（OpenAI function-calling 格式）。
 * 与桌面版 src/shared/tools.ts 对齐；移动端裁剪掉 hover/drag/upload/screenshot/
 * annotation_mode/skills/文件与命令工具（无鼠标与本地脚本环境）。
 */
object ToolSchemas {
    fun tools(): JsonArray = JsonParser.parseString(TOOLS_JSON).asJsonArray

    private const val TOOLS_JSON = """
[
  {
    "type": "function",
    "function": {
      "name": "list_tabs",
      "description": "List all open browser tabs (id, url, title, active). The human sees the same tabs.",
      "parameters": { "type": "object", "properties": {} }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "new_tab",
      "description": "Open a new tab in the browser, optionally navigating to a URL.",
      "parameters": {
        "type": "object",
        "properties": { "url": { "type": "string", "description": "URL to open; omit for a blank page" } }
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "close_tab",
      "description": "Close a tab by its id.",
      "parameters": {
        "type": "object",
        "properties": { "tabId": { "type": "number", "description": "Tab id from list_tabs" } },
        "required": ["tabId"]
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "switch_tab",
      "description": "Bring a tab to the foreground so the human sees it.",
      "parameters": {
        "type": "object",
        "properties": { "tabId": { "type": "number", "description": "Tab id from list_tabs" } },
        "required": ["tabId"]
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "navigate",
      "description": "Navigate a tab (default: active tab) to a URL.",
      "parameters": {
        "type": "object",
        "properties": {
          "url": { "type": "string", "description": "Absolute URL, e.g. https://example.com" },
          "tabId": { "type": "number" }
        },
        "required": ["url"]
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "history",
      "description": "Go back, go forward, or reload in a tab.",
      "parameters": {
        "type": "object",
        "properties": {
          "action": { "type": "string", "enum": ["back", "forward", "reload"] },
          "tabId": { "type": "number" }
        },
        "required": ["action"]
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "snapshot",
      "description": "Text snapshot of the page: compact DOM outline with [eN] refs for actionable/labeled elements. Use the refs with click/type. This is the primary way to see the page.",
      "parameters": {
        "type": "object",
        "properties": { "tabId": { "type": "number" } }
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "get_html",
      "description": "Get HTML source. Without selector: cleaned body HTML. With selector: outerHTML of the first match.",
      "parameters": {
        "type": "object",
        "properties": {
          "selector": { "type": "string" },
          "maxChars": { "type": "number", "description": "Truncate to this many chars (default 40000)" },
          "tabId": { "type": "number" }
        }
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "query",
      "description": "Query elements by CSS selector; returns details for each match (tag, id, classes, text, href, rect, visibility).",
      "parameters": {
        "type": "object",
        "properties": {
          "selector": { "type": "string" },
          "limit": { "type": "number", "description": "Max matches (default 20)" },
          "tabId": { "type": "number" }
        },
        "required": ["selector"]
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "click",
      "description": "Click an element (a ref like e12 from snapshot, or a CSS selector).",
      "parameters": {
        "type": "object",
        "properties": {
          "target": { "type": "string", "description": "ref like e12 or CSS selector" },
          "tabId": { "type": "number" }
        },
        "required": ["target"]
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "dblclick",
      "description": "Double-click an element by ref or CSS selector.",
      "parameters": {
        "type": "object",
        "properties": {
          "target": { "type": "string" },
          "tabId": { "type": "number" }
        },
        "required": ["target"]
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "type",
      "description": "Type text into an input/textarea/contenteditable, by ref or CSS selector. Optionally press Enter after.",
      "parameters": {
        "type": "object",
        "properties": {
          "target": { "type": "string" },
          "text": { "type": "string" },
          "clear": { "type": "boolean", "description": "Clear existing value first (default true)" },
          "submit": { "type": "boolean", "description": "Press Enter after typing" },
          "tabId": { "type": "number" }
        },
        "required": ["target", "text"]
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "press",
      "description": "Press a key or combo (Enter, Escape, Tab, PageDown, Control+A, Shift+Tab, ...) in the page.",
      "parameters": {
        "type": "object",
        "properties": {
          "key": { "type": "string", "description": "Single key or combo like Control+A" },
          "tabId": { "type": "number" }
        },
        "required": ["key"]
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "scroll",
      "description": "Scroll the page by dx/dy pixels (positive dy = down), or scroll an element into view when selector is given.",
      "parameters": {
        "type": "object",
        "properties": {
          "dy": { "type": "number", "description": "Pixels to scroll vertically (default 600)" },
          "dx": { "type": "number", "description": "Pixels to scroll horizontally (default 0)" },
          "selector": { "type": "string", "description": "Scroll this element into view instead" },
          "tabId": { "type": "number" }
        }
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "wait",
      "description": "Wait for time and/or page state. Provide ms, or selector (until it matches), or text (until the page contains it).",
      "parameters": {
        "type": "object",
        "properties": {
          "ms": { "type": "number", "description": "Wait this many milliseconds (max 30000)" },
          "selector": { "type": "string", "description": "Wait until this CSS selector matches" },
          "text": { "type": "string", "description": "Wait until the page contains this text" },
          "timeout": { "type": "number", "description": "Max wait for selector/text in ms (default 10000)" },
          "tabId": { "type": "number" }
        }
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "get_console",
      "description": "Read recent console messages of a tab (errors/warnings/logs) collected since load.",
      "parameters": {
        "type": "object",
        "properties": {
          "limit": { "type": "number", "description": "Max messages to return (default 50)" },
          "clear": { "type": "boolean", "description": "Clear the buffer after reading" },
          "tabId": { "type": "number" }
        }
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "search",
      "description": "Search the web in a tab (default engine: baidu). Use for looking up information; use navigate for known URLs.",
      "parameters": {
        "type": "object",
        "properties": {
          "query": { "type": "string" },
          "engine": { "type": "string", "enum": ["baidu", "bing", "google"] },
          "tabId": { "type": "number" }
        },
        "required": ["query"]
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "evaluate",
      "description": "Run JavaScript in the page and return the JSON-serializable result. Powerful; prefer snapshot/query when possible.",
      "parameters": {
        "type": "object",
        "properties": {
          "script": { "type": "string", "description": "JS expression or statements; use return x for a value" },
          "tabId": { "type": "number" }
        },
        "required": ["script"]
      }
    }
  }
]
"""
}
