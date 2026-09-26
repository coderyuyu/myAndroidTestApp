# 1. Base Configuration
$PromptFile = ".\app_prompt.txt"
$AgyLogFolder = "$env:USERPROFILE\.gemini\antigravity-cli\log"

if (-not (Test-Path $PromptFile)) {
    Write-Host "Error: app_prompt.txt not found." -ForegroundColor Red
    Exit
}

$LongPrompt = Get-Content $PromptFile -Raw
Write-Host "Loaded Prompt. Launching Google Antigravity CLI..." -ForegroundColor Cyan

# 2. Run agy agent
Write-Host "AGY is running, please operate in the active session..." -ForegroundColor Yellow
agy -i $LongPrompt
Write-Host "AGY session ended. Parsing token logs..." -ForegroundColor Green

# 3. Locate the latest log
if (-not (Test-Path $AgyLogFolder)) {
    Write-Host "Error: Log folder not found at $AgyLogFolder" -ForegroundColor Red
    Exit
}

$LatestLog = Get-ChildItem -Path $AgyLogFolder -Filter "cli-*.log" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($null -eq $LatestLog) {
    Write-Host "Error: No cli-*.log found." -ForegroundColor Red
    Exit
}

$LogFilePath = $LatestLog.FullName
Write-Host "Reading log file: $LogFilePath" -ForegroundColor Cyan

# 4. Token Processing Loop
$TotalPrompt = 0
$TotalCompletion = 0
$OutputData = @()

$LogLines = Get-Content $LogFilePath
foreach ($Line in $LogLines) {
    try {
        $Data = $Line | ConvertFrom-Json
        
        if ($null -ne $Data.step) {
            $StepNum = $Data.step
            
            $Action = "unknown"
            if ($null -ne $Data.action) { $Action = $Data.action }
            if ($null -ne $Data.tool_name) { $Action = $Data.tool_name }
            
            $Prompt = 0
            $Completion = 0
            $Total = 0
            
            if ($null -ne $Data.metadata.usage) {
                $Usage = $Data.metadata.usage
                if ($null -ne $Usage.prompt_tokens) { $Prompt = [int]$Usage.prompt_tokens }
                if ($null -ne $Usage.completion_tokens) { $Completion = [int]$Usage.completion_tokens }
                if ($null -ne $Usage.total_tokens) { $Total = [int]$Usage.total_tokens }
            }
            
            if ($Total -eq 0) { $Total = $Prompt + $Completion }
            
            $TotalPrompt += $Prompt
            $TotalCompletion += $Completion
            
            $OutputData += [PSCustomObject]@{
                "Step"         = $StepNum
                "Action"       = $Action
                "PromptTokens" = $Prompt
                "ReplyTokens"  = $Completion
                "TotalTokens"  = $Total
            }
        }
    } catch {
        # Skip general text logs safely
    }
}

# 5. Output Report
if ($OutputData.Count -gt 0) {
    $OutputData | Format-Table -AutoSize
    
    Write-Host "================================================="
    Write-Host "Cumulative Prompt Tokens: $TotalPrompt"
    Write-Host "Cumulative Reply Tokens:  $TotalCompletion"
    Write-Host "Grand Total Token Expense: " -NoNewline
    Write-Host ($TotalPrompt + $TotalCompletion) -ForegroundColor Green
    Write-Host "================================================="
} else {
    Write-Host "Warning: Log parsed, but no structured step-token data found." -ForegroundColor Yellow
}
