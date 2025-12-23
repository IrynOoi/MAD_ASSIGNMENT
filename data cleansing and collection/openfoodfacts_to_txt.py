import requests
import time
import re
import unicodedata
import random

# --- CONFIGURATION ---
OUTPUT_FILE = 'foodraw.txt'
TARGET_ALLERGENS = 150
TARGET_NO_ALLERGENS = 50
API_URL = "https://world.openfoodfacts.org/cgi/search.pl"

# Limit items per keyword to ensure diversity
MAX_ITEMS_PER_KEYWORD = 20  

TARGET_ALLERGEN_KEYWORDS = [
    "milk", "lactose", "cream", "whey", 
    "egg", 
    "peanut", 
    "nut", "almond", "cashew", "walnut", "pecan", "hazelnut",
    "wheat", "gluten", "barley", "rye", 
    "soy", "soya", 
    "fish", 
    "shellfish", "crustacean", "mollusc", "shrimp", "crab", "lobster",
    "sesame"
]

def clean_text(text):
    """
    Cleans text for safe CSV/TXT export:
    1. Normalizes accents (Café -> Cafe).
    2. Removes semicolons.
    3. Removes non-standard characters.
    """
    if not text:
        return ""
    
    # 1. Normalize unicode characters (turn é into e, ñ into n)
    # This keeps "English-like" words while removing strict accents
    text = unicodedata.normalize('NFKD', str(text)).encode('ASCII', 'ignore').decode('utf-8')
    
    # 2. Standard cleaning
    text = re.sub(r'[\r\n\t]+', ' ', text)
    text = text.replace(';', ',') # Critical for CSV structure
    text = text.replace('_', '')
    
    # 3. Remove weird symbols, keep only basic punctuation
    # Allows: a-z, 0-9, space, comma, dot, hyphen, brackets, %
    text = re.sub(r'[^a-zA-Z0-9\s,.\-%()]', '', text)
    
    return ' '.join(text.split())

def is_strictly_english(text):
    """
    STRICT ENGLISH FILTER
    Returns True only if the text is composed almost entirely of ASCII characters.
    Rejects text with Asian scripts, Arabic, Cyrillic, etc.
    """
    if not text:
        return False
        
    # 1. Check if we can encode to ASCII. If not, it has hidden non-English chars.
    try:
        text.encode('ascii')
    except UnicodeEncodeError:
        return False
        
    # 2. Double check specifically for Arabic/Asian ranges just in case
    if re.search(r'[\u0600-\u06FF]', text): # Arabic
        return False
    if re.search(r'[\u4e00-\u9fff]', text): # Chinese
        return False
    if re.search(r'[\u0400-\u04FF]', text): # Cyrillic (Russian)
        return False
        
    return True

def is_valid_ingredient_text(text):
    """Quality control for ingredients."""
    if not text: return False
    t_lower = text.lower()
    
    if len(text) < 15: return False # Too short
    
    # Reject mineral water analysis
    mineral_indicators = ['mg/l', 'ph ', 'residue', 'mineralisation', 'bicarbonates']
    if any(ind in t_lower for ind in mineral_indicators): return False

    # Reject foreign language connectors (Common in French/German products)
    foreign_stops = [' riz ', ' aux ', ' et ', ' de ', ' la ', ' und ', ' wasser ', ' zucker ', ' le ', ' les ']
    if any(ind in t_lower for ind in foreign_stops): return False

    # Reject if mostly numbers (barcode errors)
    digit_count = sum(c.isdigit() for c in text)
    letter_count = sum(c.isalpha() for c in text)
    if letter_count == 0 or (digit_count > letter_count): return False

    return True

def get_product_allergens(product):
    """
    Retrieves allergens but ONLY keeps English ('en:') tags to match the website.
    """
    # 1. Get the list of tags directly (this is the cleanest source)
    tags = product.get('allergens_tags', [])
    
    if not tags:
        return ""
        
    english_allergens = []
    
    for tag in tags:
        # 2. STRICT FILTER: Only keep tags starting with 'en:'
        if tag.startswith('en:'):
            # Remove the 'en:' prefix
            clean_tag = tag[3:].replace('-', ' ') # e.g. "en:mustard" -> "mustard"
            english_allergens.append(clean_tag)
            
    # 3. Deduplicate and Sort
    unique_parts = sorted(list(set(english_allergens)))
    
    return ', '.join(unique_parts)

def matches_target_allergens(allergen_text):
    if not allergen_text: return False
    text_lower = allergen_text.lower()
    return any(keyword in text_lower for keyword in TARGET_ALLERGEN_KEYWORDS)

def fetch_with_retry(url, params, retries=3):
    """Retry logic for network requests."""
    for i in range(retries):
        try:
            response = requests.get(url, params=params, timeout=20)
            if response.status_code == 200:
                return response.json()
        except requests.RequestException:
            print(f"      [!] Network error. Retrying ({i+1}/{retries})...")
            time.sleep(2)
    return None

def fetch_products_batch(search_terms, target_count, require_allergens, collected_ids):
    products = []
    category_label = "WITH ALLERGENS" if require_allergens else "NO ALLERGENS"
    
    print(f"\n--- Searching for {target_count} products [{category_label}] ---")

    # Copy list to avoid modifying original
    search_terms_shuffled = search_terms.copy()
    
    for term in search_terms_shuffled:
        if len(products) >= target_count:
            break

        print(f"   > Searching term: '{term}'...")
        
        term_count = 0 

        params = {
            'search_terms': term,
            'page_size': 50, 
            'json': 1,
            'lc': 'en',
            # We specifically request ONLY English fields now
            'fields': 'code,product_name_en,ingredients_text_en,allergens,allergens_tags,allergens_from_ingredients'
        }

        data = fetch_with_retry(API_URL, params)
        if not data: continue

        items = data.get('products', [])

        for p in items:
            if len(products) >= target_count: break
            
            # Stop if we have enough items for this specific keyword (Diversity Check)
            if require_allergens and term_count >= MAX_ITEMS_PER_KEYWORD:
                print(f"      -> Hit quota for '{term}'. Moving to next category.")
                break

            pid = p.get('code')
            
            # --- CRITICAL CHANGE: ONLY ACCEPT 'en' FIELDS ---
            # If product_name_en is missing, we SKIP. We do NOT fallback to generic product_name.
            name = p.get('product_name_en')
            ingredients = p.get('ingredients_text_en')

            # --- Validation ---
            if not pid or not name or not ingredients: continue
            if pid in collected_ids: continue
            
            # --- LANGUAGE FILTERS ---
            # 1. Normalize accents (turn é into e)
            clean_name = clean_text(name)
            clean_ing = clean_text(ingredients)
            
            # 2. Strict English Check (Reject if normalization failed or hidden chars exist)
            if not is_strictly_english(clean_name) or not is_strictly_english(clean_ing):
                # print(f"      [x] Rejected non-English: {clean_name[:20]}")
                continue
            
            if not is_valid_ingredient_text(clean_ing): continue

            # --- Allergen Logic ---
            allergen_str = get_product_allergens(p)
            has_allergens = bool(allergen_str)

            if require_allergens:
                # Must have allergens AND match one of the 9 targets
                if not has_allergens: continue
                if not matches_target_allergens(allergen_str): continue
            else:
                # Must NOT have allergens
                if has_allergens: continue

            # --- Add ---
            products.append({
                'name': clean_name,
                'ingredients': clean_ing,
                'allergens': clean_text(allergen_str) if require_allergens else "",
                'link': f"https://world.openfoodfacts.org/product/{pid}"
            })

            collected_ids.add(pid)
            term_count += 1
            print(f"     + [{len(products)}/{target_count}] Added ({term}): {clean_name[:25]}...")

        time.sleep(1) 

    return products

def save_to_file(list_with, list_without):
    print(f"\n--- Saving Data to {OUTPUT_FILE} ---")
    try:
        with open(OUTPUT_FILE, 'w', encoding='utf-8') as f:
            # First 150: With Allergens
            for i, p in enumerate(list_with, 1):
                f.write(f"{i};{p['name']};{p['ingredients']};{p['allergens']};{p['link']}\n")
            
            # Next 50: Without Allergens (IDs 151-200)
            for i, p in enumerate(list_without, 151):
                f.write(f"{i};{p['name']};{p['ingredients']};{p['allergens']};{p['link']}\n")
        print("   ✅ Success! File written.")
    except IOError as e:
        print(f"   ❌ Error writing file: {e}")

def main():
    print("="*60)
    print("  OPEN FOOD FACTS COLLECTOR (STRICT ENGLISH ONLY)")
    print("="*60)
    
    global_ids = set()

    # Search terms
    allergen_keywords = ["milk", "egg", "peanut", "nut", "wheat", "soy", "fish", "shellfish", "sesame", "shrimp", "bread", "cake"]
    safe_keywords = ["rice", "salt", "sugar", "honey", "oil", "tea", "coffee", "vinegar", "fruit", "juice", "jam"]

    # Step 1: Get Allergens
    list_allergens = fetch_products_batch(allergen_keywords, TARGET_ALLERGENS, True, global_ids)
    
    # Fallback if not enough
    if len(list_allergens) < TARGET_ALLERGENS:
        print("\n   ...Need more allergen products, widening search...")
        extra_terms = ["biscuit", "cookie", "yogurt", "cheese", "snack", "pasta", "noodle", "chocolate"]
        list_allergens += fetch_products_batch(extra_terms, TARGET_ALLERGENS, True, global_ids)

    # Step 2: Get Safe products
    list_safe = fetch_products_batch(safe_keywords, TARGET_NO_ALLERGENS, False, global_ids)

    # Step 3: Save
    save_to_file(list_allergens, list_safe)
    
    print(f"\nFinal Stats:")
    print(f"Allergen Products: {len(list_allergens)}/150")
    print(f"Safe Products:     {len(list_safe)}/50")

if __name__ == "__main__":
    main()