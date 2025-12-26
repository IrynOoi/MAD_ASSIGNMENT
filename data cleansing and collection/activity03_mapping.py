# activity03_mapping.py
import pandas as pd
import google.generativeai as genai
import time
import json
from google.api_core import exceptions

# --- CONFIGURATION ---
INPUT_FILE = 'foodraw.xlsx'
OUTPUT_FILE = 'foodpreprocessed.xlsx'
BATCH_SIZE = 20

# 🔐 YOUR API KEY
API_KEY = "" 
genai.configure(api_key=API_KEY)

# ... [Keep the get_working_model function unchanged] ...
def get_working_model():
    # (Same code as before)
    print("🔍 Testing available models for quota health...")
    try:
        available_models = []
        for m in genai.list_models():
            if 'generateContent' in m.supported_generation_methods:
                available_models.append(m.name)
        
        priority_list = ["models/gemini-2.5-flash", "models/gemini-1.5-flash", "models/gemini-1.5-pro", "models/gemini-pro"]
        for model_name in priority_list:
            if model_name in available_models:
                print(f"   👉 Testing: {model_name}...", end=" ")
                try:
                    test_model = genai.GenerativeModel(model_name)
                    test_model.generate_content("test") 
                    print("✅ Working!")
                    return test_model
                except Exception:
                    print("❌ Quota Exhausted/Error. Skipping.")
                    continue
        print("❌ No working models found.")
        return None
    except Exception as e:
        print(f"❌ Error checking models: {e}")
        return None

model = get_working_model()

def map_allergens_batch(text_list):
    """
    Sends a list of allergens to Gemini.
    Skips "empty" rows to follow assignment requirements strictly.
    """
    clean_list = []
    
    # 1. Pre-process list to handle "empty" values
    for x in text_list:
        val = str(x).strip()
        # If allergenraw is empty, we send a placeholder that AI ignores, 
        # or we handle it later. Here we send "" to signify no data.
        if pd.isna(x) or val == "" or val.lower() == "empty":
            clean_list.append("") 
        else:
            clean_list.append(val)
    
    if not model:
        return [""] * len(text_list)

    # 2. Prepare AI Prompt
    # Only ask AI to map actual text. We tell it "" means no allergens.
    prompt = f"""
    Map these RAW ALLERGEN texts to the 9 standard categories: 
    [milk, egg, peanut, tree nut, wheat, soy, fish, shellfish, sesame].
    
    Rules:
    1. Output a JSON list of strings.
    2. Maintain exact order.
    3. If input is empty string "", return empty string "".
    4. If input is "no allergens", return "".
    5. Mapping examples: "cashew" -> "tree nut", "shrimp" -> "shellfish".
    
    Input:
    {json.dumps(clean_list)}
    """
    
    max_retries = 3
    for attempt in range(max_retries):
        try:
            response = model.generate_content(prompt)
            result_text = response.text.strip()
            if result_text.startswith("```json"):
                result_text = result_text[7:-3].strip()
            elif result_text.startswith("```"):
                result_text = result_text[3:-3].strip()
            
            mapped_data = json.loads(result_text)
            
            if len(mapped_data) != len(text_list):
                print(f"   [!] Batch mismatch. Filling with blanks.")
                return [""] * len(text_list)
            return mapped_data

        except exceptions.ResourceExhausted:
            print(f"   [⏳] Rate limit hit. Waiting 10s...")
            time.sleep(10)
        except Exception as e:
            print(f"   [!] Error: {e}")
            return [""] * len(text_list)

    return [""] * len(text_list)

def main():
    print("=" * 60)
    print("  ACTIVITY 03: DATA MAPPING (STRICT: ALLERGENSRAW)")
    print("=" * 60)

    if not model:
        return

    # 1. Read input
    try:
        print(f"📂 Reading file: {INPUT_FILE}...")
        df = pd.read_excel(INPUT_FILE)
        
        # --- STRICT CHANGE: TARGET 'allergensraw' ---
        target_col = 'allergensraw' 
        
        if target_col not in df.columns:
            # Check if it's named 'allergens' instead (common mismatch)
            if 'allergens' in df.columns:
                target_col = 'allergens'
                print(f"   ⚠️ 'allergensraw' not found. Using 'allergens' instead.")
            else:
                print(f"❌ Error: Column '{target_col}' not found.")
                return
                
    except Exception as e:
        print(f"❌ Error reading file: {e}")
        return

    # 2. Batch Process
    total_rows = len(df)
    all_results = []

    print(f"\n🚀 Processing {total_rows} items (Target: {target_col})...")

    for i in range(0, total_rows, BATCH_SIZE):
        batch = df[target_col].iloc[i : i + BATCH_SIZE].tolist()
        print(f"   Processing {i} to {i + len(batch)}...", end="\r")
        
        results = map_allergens_batch(batch)
        all_results.extend(results)
        time.sleep(2) 

# 3. Save
    df['allergensmapped'] = all_results
    
    # Reorder columns
    desired_order = ['id', 'name', 'link', 'ingredients', 'allergensraw', 'allergensmapped']
    final_cols = [c for c in desired_order if c in df.columns]
    df = df[final_cols]

    # --- 🧹 NEW CLEANING BLOCK 🧹 ---
    print("🧹 Cleaning formatting (removing brackets)...")
    def clean_text(text):
        # Convert to string and remove [ ] ' "
        text = str(text)
        for char in ["[", "]", "'", '"']:
            text = text.replace(char, "")
        return text.strip()

    df['allergensmapped'] = df['allergensmapped'].apply(clean_text)
    # --------------------------------

    try:
        print(f"\n\n💾 Saving to: {OUTPUT_FILE}...")
        df.to_excel(OUTPUT_FILE, index=False)
        print("✅ Success! Mapping completed.")
        print("-" * 50)
        print(df.tail()) 
        print("-" * 50)
    except Exception as e:
        print(f"❌ Error saving file: {e}")

if __name__ == "__main__":
    main()