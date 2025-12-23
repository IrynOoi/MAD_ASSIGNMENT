import pandas as pd
import google.generativeai as genai
import time

# --- CONFIGURATION (設定) ---
INPUT_FILE = 'foodraw.xlsx'
OUTPUT_FILE = 'foodpreprocesseds.xlsx'
API_KEY = "AIzaSyBJlCaa2AgaTN0rqale01xqW_jJk0SDMVE"  # 你的 API Key

# 設定 Gemini AI
genai.configure(api_key=API_KEY)

def get_available_model():
    """
    自動偵測帳號可用的模型，避免 404 錯誤。
    """
    print("🔍 Detecting available models for your API Key...")
    try:
        for m in genai.list_models():
            if 'generateContent' in m.supported_generation_methods:
                # 優先尋找 gemini 系列
                if 'gemini' in m.name:
                    print(f"   ✅ Found working model: {m.name}")
                    return genai.GenerativeModel(m.name)
        
        # 如果沒找到 gemini，隨便回傳第一個支援生成的模型
        print("   ⚠️ No specific 'gemini' model found, trying default fallback.")
        return genai.GenerativeModel('gemini-pro')
        
    except Exception as e:
        print(f"   ❌ Error listing models: {e}")
        # 最後手段：直接硬試 gemini-1.5-flash
        return genai.GenerativeModel('gemini-1.5-flash')

# 初始化模型 (自動選擇)
model = get_available_model()

def ask_ai_to_map(allergen_text):
    if pd.isna(allergen_text) or str(allergen_text).strip() == "":
        return ""

    prompt = f"""
    Task: Map the input allergen text to exactly one or more of these 9 specific categories:
    [milk, egg, peanut, tree nut, wheat, soy, fish, shellfish, sesame].
    
    Strict Rules:
    1. Input: "{allergen_text}"
    2. Output: Only return the category names from the list above.
    3. Format: Lowercase, separated by a comma if multiple match.
    4. Mapping Logic:
       - Almonds, cashews, walnuts -> "tree nut"
       - Shrimp, crab, lobster -> "shellfish"
       - Gluten, barley, rye -> "wheat"
    5. If input doesn't match the 9 categories, return empty string.
    6. No explanation, just the words.
    """

    try:
        response = model.generate_content(prompt)
        if response.text:
            return response.text.strip().lower()
        return ""
    except Exception as e:
        # 如果遇到錯誤，印出但不中斷程式
        print(f"   [!] AI Error: {e}")
        return ""

def main():
    print("="*60)
    print("  ACTIVITY 03: DATA MAPPING (AUTO-DETECT MODEL)")
    print("="*60)

    # 1. 讀取檔案
    try:
        print(f"📂 Reading file: {INPUT_FILE}...")
        df = pd.read_excel(INPUT_FILE)
        print(f"   Loaded {len(df)} rows.")
        
        target_col = 'allergensraw'
        if target_col not in df.columns:
            if 'allergens' in df.columns:
                target_col = 'allergens'
            else:
                print(f"❌ Error: Column '{target_col}' not found.")
                return
    except Exception as e:
        print(f"❌ Error reading file: {e}")
        return

    # 2. 執行 AI Mapping
    print("\n🤖 AI is mapping allergens...")
    
    mapped_results = []
    total = len(df)
    
    for index, row in df.iterrows():
        raw_text = row.get(target_col, '')
        print(f"   Processing {index+1}/{total}...", end="\r")
        
        result = ask_ai_to_map(raw_text)
        mapped_results.append(result)
        time.sleep(1.5) # 保持延遲以防錯誤
            
    df['allergensmapped'] = mapped_results

    # 3. 存檔
    try:
        print(f"\n\n💾 Saving to: {OUTPUT_FILE}...")
        df.to_excel(OUTPUT_FILE, index=False)
        print("✅ Success! Mapping completed.")
        print("-" * 60)
        print(df[[target_col, 'allergensmapped']].head())
        print("-" * 60)
    except Exception as e:
        print(f"\n❌ Error saving file: {e}")

if __name__ == "__main__":
    main()