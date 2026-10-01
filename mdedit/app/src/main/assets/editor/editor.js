/**
 * Lightweight, robust WYSIWYG Markdown Editor Engine for Android MDEdit.
 * Converts between Markdown and rich DOM in real-time, supports full toolbar commands,
 * and communicates bidirectionally with Android Kotlin via window.AndroidBridge.
 */
(function() {
  'use strict';

  const editor = document.getElementById('editor');
  let isUpdatingInternally = false;
  let inputDebounceTimer = null;

  // --- Markdown Parser (Markdown -> HTML) ---
  function markdownToHtml(md) {
    if (!md) return '<p><br></p>';

    const lines = md.split(/\r?\n/);
    let html = '';
    let inCodeBlock = false;
    let codeBlockContent = '';
    let inList = null; // 'ul' or 'ol'
    let inBlockquote = false;

    function closeList() {
      if (inList) {
        html += `</${inList}>`;
        inList = null;
      }
    }

    function closeBlockquote() {
      if (inBlockquote) {
        html += '</blockquote>';
        inBlockquote = false;
      }
    }

    function parseInline(text) {
      // Escape HTML entities first (except intentional tags)
      let s = text
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');

      // Inline code: `code`
      s = s.replace(/`([^`]+)`/g, '<code>$1</code>');
      // Bold & Italic: ***text***
      s = s.replace(/\*\*\*([^*]+)\*\*\*/g, '<strong><em>$1</em></strong>');
      // Bold: **text** or __text__
      s = s.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
      s = s.replace(/__([^_]+)__/g, '<strong>$1</strong>');
      // Italic: *text* or _text_
      s = s.replace(/\*([^*]+)\*/g, '<em>$1</em>');
      s = s.replace(/_([^_]+)_/g, '<em>$1</em>');
      // Strikethrough: ~~text~~
      s = s.replace(/~~([^~]+)~~/g, '<del>$1</del>');
      // Links: [title](url)
      s = s.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2">$1</a>');

      return s;
    }

    for (let i = 0; i < lines.length; i++) {
      let line = lines[i];

      // Fenced code blocks ```
      if (line.trim().startsWith('```')) {
        if (!inCodeBlock) {
          closeList();
          closeBlockquote();
          inCodeBlock = true;
          codeBlockContent = '';
        } else {
          inCodeBlock = false;
          const escaped = codeBlockContent
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;');
          html += `<pre><code>${escaped}</code></pre>`;
        }
        continue;
      }

      if (inCodeBlock) {
        codeBlockContent += (codeBlockContent ? '\n' : '') + line;
        continue;
      }

      // Horizontal Rule ---, ***, ___
      if (/^(?:---|\*\*\*|___)\s*$/.test(line.trim())) {
        closeList();
        closeBlockquote();
        html += '<hr>';
        continue;
      }

      // Headers: # H1, ## H2, ### H3, #### H4, ##### H5, ###### H6
      const headerMatch = line.match(/^(#{1,6})\s+(.*)$/);
      if (headerMatch) {
        closeList();
        closeBlockquote();
        const level = headerMatch[1].length;
        html += `<h${level}>${parseInline(headerMatch[2])}</h${level}>`;
        continue;
      }

      // Blockquotes: > quote
      const bqMatch = line.match(/^>\s*(.*)$/);
      if (bqMatch) {
        closeList();
        if (!inBlockquote) {
          html += '<blockquote>';
          inBlockquote = true;
        }
        html += `<p>${parseInline(bqMatch[1])}</p>`;
        continue;
      } else {
        closeBlockquote();
      }

      // Checklists: - [ ] or - [x]
      const checkMatch = line.match(/^[-*]\s+\[([ xX])\]\s+(.*)$/);
      if (checkMatch) {
        if (inList !== 'ul') {
          closeList();
          html += '<ul>';
          inList = 'ul';
        }
        const isChecked = checkMatch[1].toLowerCase() === 'x';
        html += `<li class="task-list-item"><input type="checkbox" ${isChecked ? 'checked' : ''}>${parseInline(checkMatch[2])}</li>`;
        continue;
      }

      // Unordered List: - item, * item, + item
      const ulMatch = line.match(/^[-*+]\s+(.*)$/);
      if (ulMatch) {
        if (inList !== 'ul') {
          closeList();
          html += '<ul>';
          inList = 'ul';
        }
        html += `<li>${parseInline(ulMatch[1])}</li>`;
        continue;
      }

      // Ordered List: 1. item
      const olMatch = line.match(/^\d+\.\s+(.*)$/);
      if (olMatch) {
        if (inList !== 'ol') {
          closeList();
          html += '<ol>';
          inList = 'ol';
        }
        html += `<li>${parseInline(olMatch[1])}</li>`;
        continue;
      }

      // Normal text or empty line
      closeList();

      if (line.trim() === '') {
        html += '<p><br></p>';
      } else {
        html += `<p>${parseInline(line)}</p>`;
      }
    }

    closeList();
    closeBlockquote();

    return html || '<p><br></p>';
  }

  // --- HTML Serializer (HTML -> Markdown) ---
  function htmlToMarkdown(rootNode) {
    function processNode(node) {
      if (!node) return '';

      // Text node
      if (node.nodeType === Node.TEXT_NODE) {
        return node.nodeValue;
      }

      if (node.nodeType !== Node.ELEMENT_NODE) {
        return '';
      }

      const tagName = node.tagName.toLowerCase();
      let inner = '';
      for (let i = 0; i < node.childNodes.length; i++) {
        inner += processNode(node.childNodes[i]);
      }

      switch (tagName) {
        case 'h1':
          return `\n# ${inner.trim()}\n\n`;
        case 'h2':
          return `\n## ${inner.trim()}\n\n`;
        case 'h3':
          return `\n### ${inner.trim()}\n\n`;
        case 'h4':
          return `\n#### ${inner.trim()}\n\n`;
        case 'h5':
          return `\n##### ${inner.trim()}\n\n`;
        case 'h6':
          return `\n###### ${inner.trim()}\n\n`;
        case 'p':
        case 'div':
          if (!inner.trim() || inner === '<br>') return '\n\n';
          return `\n${inner.trim()}\n\n`;
        case 'strong':
        case 'b':
          return inner ? `**${inner}**` : '';
        case 'em':
        case 'i':
          return inner ? `*${inner}*` : '';
        case 'del':
        case 's':
        case 'strike':
          return inner ? `~~${inner}~~` : '';
        case 'code':
          if (node.parentNode && node.parentNode.tagName.toLowerCase() === 'pre') {
            return inner;
          }
          return `\`${inner}\``;
        case 'pre':
          return `\n\`\`\`\n${node.textContent.trim()}\n\`\`\`\n\n`;
        case 'blockquote':
          const lines = inner.trim().split(/\r?\n/).map(l => l.trim()).filter(l => l.length > 0);
          return '\n' + lines.map(l => `> ${l}`).join('\n') + '\n\n';
        case 'ul': {
          let res = '\n';
          for (let i = 0; i < node.children.length; i++) {
            const li = node.children[i];
            const checkbox = li.querySelector('input[type="checkbox"]');
            if (checkbox) {
              const checked = checkbox.checked ? '[x]' : '[ ]';
              const text = li.textContent.trim();
              res += `- ${checked} ${text}\n`;
            } else {
              res += `- ${processNode(li).trim()}\n`;
            }
          }
          return res + '\n';
        }
        case 'ol': {
          let res = '\n';
          for (let i = 0; i < node.children.length; i++) {
            const li = node.children[i];
            res += `${i + 1}. ${processNode(li).trim()}\n`;
          }
          return res + '\n';
        }
        case 'li':
          return inner.trim();
        case 'hr':
          return '\n---\n\n';
        case 'br':
          return '\n';
        case 'a':
          const href = node.getAttribute('href') || '';
          return `[${inner}](${href})`;
        default:
          return inner;
      }
    }

    let md = processNode(rootNode);
    // Cleanup multiple consecutive blank lines
    md = md.replace(/\n{3,}/g, '\n\n').trim();
    return md;
  }

  // --- Active Formatting Detection ---
  function queryActiveFormats() {
    const formats = {
      isBold: document.queryCommandState('bold'),
      isItalic: document.queryCommandState('italic'),
      isStrike: document.queryCommandState('strikeThrough'),
      isBulletList: document.queryCommandState('insertUnorderedList'),
      isNumberedList: document.queryCommandState('insertOrderedList'),
      headerLevel: 0,
      isBlockquote: false,
      isCodeBlock: false
    };

    const sel = window.getSelection();
    if (sel && sel.anchorNode) {
      let curr = sel.anchorNode.nodeType === Node.ELEMENT_NODE ? sel.anchorNode : sel.anchorNode.parentElement;
      while (curr && curr !== editor) {
        const tag = curr.tagName ? curr.tagName.toLowerCase() : '';
        if (/^h[1-6]$/.test(tag)) {
          formats.headerLevel = parseInt(tag.charAt(1), 10);
        } else if (tag === 'blockquote') {
          formats.isBlockquote = true;
        } else if (tag === 'pre' || tag === 'code') {
          formats.isCodeBlock = true;
        }
        curr = curr.parentElement;
      }
    }

    return formats;
  }

  function notifySelectionChange() {
    if (window.AndroidBridge && window.AndroidBridge.onSelectionChanged) {
      const state = queryActiveFormats();
      window.AndroidBridge.onSelectionChanged(JSON.stringify(state));
    }
  }

  function notifyContentChange() {
    if (isUpdatingInternally) return;
    const md = htmlToMarkdown(editor);
    if (window.AndroidBridge && window.AndroidBridge.onContentChanged) {
      window.AndroidBridge.onContentChanged(md);
    }
  }

  // Listeners
  editor.addEventListener('input', function() {
    clearTimeout(inputDebounceTimer);
    inputDebounceTimer = setTimeout(function() {
      notifyContentChange();
      notifySelectionChange();
    }, 150);
  });

  document.addEventListener('selectionchange', function() {
    notifySelectionChange();
  });

  // Handle task checkboxes toggle
  editor.addEventListener('change', function(e) {
    if (e.target && e.target.type === 'checkbox') {
      notifyContentChange();
    }
  });

  // --- Exposed API to Kotlin Bridge ---
  window.EditorAPI = {
    setMarkdown: function(md) {
      isUpdatingInternally = true;
      try {
        editor.innerHTML = markdownToHtml(md || '');
      } finally {
        isUpdatingInternally = false;
      }
    },

    getMarkdown: function() {
      return htmlToMarkdown(editor);
    },

    formatBold: function() {
      document.execCommand('bold', false, null);
      notifyContentChange();
      notifySelectionChange();
    },

    formatItalic: function() {
      document.execCommand('italic', false, null);
      notifyContentChange();
      notifySelectionChange();
    },

    formatStrike: function() {
      document.execCommand('strikeThrough', false, null);
      notifyContentChange();
      notifySelectionChange();
    },

    formatHeader: function(level) {
      const current = queryActiveFormats().headerLevel;
      if (current === level) {
        document.execCommand('formatBlock', false, '<p>');
      } else {
        document.execCommand('formatBlock', false, `<h${level}>`);
      }
      notifyContentChange();
      notifySelectionChange();
    },

    formatBulletList: function() {
      document.execCommand('insertUnorderedList', false, null);
      notifyContentChange();
      notifySelectionChange();
    },

    formatNumberedList: function() {
      document.execCommand('insertOrderedList', false, null);
      notifyContentChange();
      notifySelectionChange();
    },

    formatBlockquote: function() {
      const current = queryActiveFormats().isBlockquote;
      if (current) {
        document.execCommand('formatBlock', false, '<p>');
      } else {
        document.execCommand('formatBlock', false, '<blockquote>');
      }
      notifyContentChange();
      notifySelectionChange();
    },

    formatCodeBlock: function() {
      const sel = window.getSelection();
      if (!sel || !sel.rangeCount) return;
      const text = sel.toString();
      const pre = document.createElement('pre');
      const code = document.createElement('code');
      code.textContent = text || 'code here';
      pre.appendChild(code);

      const range = sel.getRangeAt(0);
      range.deleteContents();
      range.insertNode(pre);
      notifyContentChange();
      notifySelectionChange();
    },

    formatChecklist: function() {
      const sel = window.getSelection();
      if (!sel || !sel.rangeCount) return;
      document.execCommand('insertUnorderedList', false, null);
      const parentLi = sel.anchorNode ? sel.anchorNode.parentElement.closest('li') : null;
      if (parentLi) {
        parentLi.className = 'task-list-item';
        const checkbox = document.createElement('input');
        checkbox.type = 'checkbox';
        parentLi.insertBefore(checkbox, parentLi.firstChild);
      }
      notifyContentChange();
      notifySelectionChange();
    },

    formatHorizontalRule: function() {
      document.execCommand('insertHorizontalRule', false, null);
      notifyContentChange();
      notifySelectionChange();
    },

    undo: function() {
      document.execCommand('undo', false, null);
      notifyContentChange();
      notifySelectionChange();
    },

    redo: function() {
      document.execCommand('redo', false, null);
      notifyContentChange();
      notifySelectionChange();
    },

    clearFormatting: function() {
      document.execCommand('removeFormat', false, null);
      document.execCommand('formatBlock', false, '<p>');
      notifyContentChange();
      notifySelectionChange();
    },

    setTheme: function(bgColor, textColor, accentColor, isDark) {
      const root = document.documentElement;
      if (bgColor) root.style.setProperty('--bg-color', bgColor);
      if (textColor) root.style.setProperty('--text-color', textColor);
      if (accentColor) root.style.setProperty('--accent-color', accentColor);
      if (isDark) {
        root.style.setProperty('--code-bg', '#1e1e24');
        root.style.setProperty('--border-color', '#333333');
        root.style.setProperty('--quote-bg', '#18191c');
        root.style.setProperty('--quote-border', accentColor || '#64b5f6');
      } else {
        root.style.setProperty('--code-bg', '#f4f4f6');
        root.style.setProperty('--border-color', '#e0e0e0');
        root.style.setProperty('--quote-bg', '#f8f9fa');
        root.style.setProperty('--quote-border', accentColor || '#0066cc');
      }
    },

    focusEditor: function() {
      editor.focus();
    }
  };

  // Signal ready to Android Kotlin
  if (window.AndroidBridge && window.AndroidBridge.onEditorReady) {
    window.AndroidBridge.onEditorReady();
  }
})();
