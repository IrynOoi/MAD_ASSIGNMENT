import pandas as pd
import google.generativeai as genai
import time

# --- CONFIGURATION ---
INPUT_FILE = 'foodraw.xlsx'
OUTPUT_FILE = 'foodpreprocesseds.xlsx'
API_KEY = "AIzaSyBJlCaa2AgaTN0rqale01xqW_jJk0SDMVE"  # Your API Key

# Configure Gemini AI
genai.configure(api_key=API_KEY)

# ✅ Directly use Gemini 2.5 Flash (confirmed working)
model = genai.GenerativeModel("models/gemini-2.5-flash")


def ask_ai_to_map(allergen_text):
    if pd.isna(allergen_text) or str(allergen_text).strip() == "":
        return ""

    prompt = f"""
Task: Map the input allergen text to exactly one or more of these 9 specific categories:
[milk, egg, peanut, tree nut, wheat, soy, fish, shellfish, sesame].

Strict Rules:
1. Input: "{allergen_text}"
2. Output: Only return the category names from the list above.
3. Format: Lowercase, separated by commas if multiple match.
4. Mapping Logic:
   - Almonds, cashews, walnuts → "tree nut"
   - Shrimp, crab, lobster → "shellfish"
   - Gluten, barley, rye → "wheat"
5. If the input does not match any of the 9 categories, return an empty string.
6. No explanation, only the category names.
"""

    try:
        response = model.generate_content(prompt)
        if response.text:
            return response.text.strip().lower()
        return ""
    except Exception as e:
        print(f"   [!] AI Error: {e}")
        return ""


def main():
    print("=" * 60)
    print("  ACTIVITY 03: DATA MAPPING (GEMINI 2.5 FLASH)")
    print("=" * 60)

    # 1. Read input file
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

    # 2. Run AI mapping
    print("\n🤖 AI is mapping allergens...")

    mapped_results = []
    total = len(df)

    for index, row in df.iterrows():
        raw_text = row.get(target_col, '')
        print(f"   Processing {index + 1}/{total}...", end="\r")

        result = ask_ai_to_map(raw_text)
        mapped_results.append(result)

        # Prevent API rate limiting
        time.sleep(1.5)

    df['allergensmapped'] = mapped_results

    # 3. Save output file
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
