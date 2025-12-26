#  openfoodfacts_to_txt.py  
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
MAX_ITEMS_PER_KEYWORD = 20  

# Allergen keywords (these are what we're looking for)
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

# Allowed characters (strict English alphabet + common food symbols)
ALLOWED_CHARS_REGEX = r'^[a-zA-Z0-9\s,\-\.%()\[\]/\+&:\'"]+$'

def is_english_alphabet_text(text):
    """
    STRICT ENGLISH CHECK - Only allows text composed primarily of English alphabet
    Rejects any text that contains significant non-English characters
    """
    if not text:
        return False
    
    # Remove common food-related symbols and numbers first
    temp_text = re.sub(r'[0-9\s,\-\.%()\[\]/\+&:\'"]', '', text)
    
    # After removing allowed symbols, only English letters should remain
    if not temp_text:
        return True  # Text was only symbols/numbers
        
    # Check if remaining characters are English letters
    english_letter_ratio = sum(1 for c in temp_text if 'a' <= c.lower() <= 'z') / len(temp_text)
    
    # Require at least 80% English letters
    return english_letter_ratio >= 0.8

def clean_text(text):
    """
    Cleans text while preserving ALL ingredient information
    """
    if not text:
        return ""
    
    # Normalize Unicode (accented characters to plain ASCII)
    text = unicodedata.normalize('NFKD', str(text))
    text = ''.join(c for c in text if not unicodedata.combining(c))
    
    # Replace problematic characters but keep content
    text = re.sub(r'[\r\n\t]+', ' ', text)  # Replace newlines/tabs with space
    text = text.replace(';', ',')  # Critical for CSV
    
    # Preserve important food-related characters
    # Remove only truly problematic characters
    text = re.sub(r'[\u0000-\u001F\u007F-\u009F]', '', text)  # Control chars
    text = re.sub(r'[<>]', '', text)  # Remove < and > which can break formats
    
    # Ensure the text ends with no trailing spaces
    return ' '.join(text.split())

def is_valid_ingredient_text(text):
    """Quality control for ingredients - ensures completeness"""
    if not text:
        return False
    
    text = text.strip()
    if len(text) < 20:  # Ingredients should have some detail
        return False
    
    # Must contain actual ingredient words, not just numbers or codes
    word_count = len([w for w in text.split() if len(w) > 2])
    if word_count < 3:  # At least 3 substantive words
        return False
    
    # Check if it appears to be complete ingredient list
    # Should have common separators or ingredient indicators
    has_ingredient_indicators = any(
        marker in text.lower() 
        for marker in [',', 'and', 'with', 'contain', 'ingredient']
    )
    
    if not has_ingredient_indicators and len(text) < 50:
        return False
    
    # Reject mineral water analysis or chemical compositions
    if re.search(r'\b\d+\s*(mg|g|ml|%)\b', text):
        # Check if it's JUST measurements without actual food
        food_keywords = ['sugar', 'salt', 'water', 'juice', 'extract', 'oil', 'flour']
        if not any(keyword in text.lower() for keyword in food_keywords):
            # Count number of food-like words vs measurements
            measurement_count = len(re.findall(r'\b\d+\s*(mg|g|ml|%)\b', text))
            word_count = len(text.split())
            if measurement_count > word_count / 3:  # Too many measurements
                return False
    
    return True

def get_product_allergens(product):
    """
    Retrieves allergens with English tags only
    Returns EMPTY if no allergens found
    """
    # Check multiple sources for allergens
    allergen_sources = [
        product.get('allergens_tags', []),
        product.get('allergens_from_ingredients', ''),
        product.get('allergens', '')
    ]
    
    english_allergens = []
    
    # First, check the structured allergens_tags
    for tag in allergen_sources[0]:
        if tag.startswith('en:'):
            clean_tag = tag[3:].replace('-', ' ').strip()
            if clean_tag and clean_tag not in english_allergens:
                english_allergens.append(clean_tag)
    
    # If no structured tags, try to parse text fields
    if not english_allergens:
        for text_source in allergen_sources[1:]:
            if text_source and isinstance(text_source, str):
                # Simple parsing of text allergens
                text_lower = text_source.lower()
                for keyword in TARGET_ALLERGEN_KEYWORDS:
                    if keyword in text_lower and keyword not in english_allergens:
                        english_allergens.append(keyword)
    
    # Deduplicate and sort
    unique_allergens = sorted(list(set(english_allergens)))
    
    if unique_allergens:
        return ', '.join(unique_allergens)
    else:
        return "EMPTY"

def matches_target_allergens(allergen_text):
    """Check if allergen text contains our target allergens"""
    if not allergen_text or allergen_text == "EMPTY":
        return False
    
    text_lower = allergen_text.lower()
    return any(keyword in text_lower for keyword in TARGET_ALLERGEN_KEYWORDS)

def fetch_with_retry(url, params, retries=3):
    """Retry logic for network requests"""
    for i in range(retries):
        try:
            response = requests.get(url, params=params, timeout=25)
            if response.status_code == 200:
                return response.json()
            elif response.status_code == 429:  # Rate limit
                wait_time = 30 * (i + 1)  # Exponential backoff
                print(f"      [!] Rate limited. Waiting {wait_time} seconds...")
                time.sleep(wait_time)
        except requests.RequestException as e:
            print(f"      [!] Network error: {e}. Retrying ({i+1}/{retries})...")
            time.sleep(5)
    return None

def validate_product_data(product):
    """
    Comprehensive validation of product data
    Returns (is_valid, cleaned_data_dict) or (False, None)
    """
    # Get all available fields
    product_id = product.get('code')
    
    # Try multiple language fields, prioritizing English
    name = product.get('product_name_en') or product.get('product_name') or ""
    ingredients = product.get('ingredients_text_en') or product.get('ingredients_text') or ""
    
    # Critical: Must have both name and ingredients
    if not product_id or not name or not ingredients:
        return False, None
    
    # Check language - both must be primarily English alphabet
    if not is_english_alphabet_text(name) or not is_english_alphabet_text(ingredients):
        return False, None
    
    # Clean the text
    clean_name = clean_text(name)
    clean_ing = clean_text(ingredients)
    
    # Verify cleaning didn't remove essential content
    if len(clean_name) < 3 or len(clean_ing) < 10:
        return False, None
    
    # Ensure ingredients are valid and complete
    if not is_valid_ingredient_text(clean_ing):
        return False, None
    
    # Get allergens
    allergens = get_product_allergens(product)
    
    return True, {
        'id': product_id,
        'name': clean_name,
        'ingredients': clean_ing,
        'allergens': allergens,
        'link': f"https://world.openfoodfacts.org/product/{product_id}"
    }

def fetch_products_batch(search_terms, target_count, require_allergens, collected_ids):
    """
    Fetch products with comprehensive validation
    """
    products = []
    category_label = "WITH ALLERGENS" if require_allergens else "NO ALLERGENS"
    
    print(f"\n--- Searching for {target_count} products [{category_label}] ---")
    
    # Shuffle search terms for diversity
    search_terms_shuffled = search_terms.copy()
    random.shuffle(search_terms_shuffled)
    
    for term in search_terms_shuffled:
        if len(products) >= target_count:
            break
            
        print(f"   > Searching term: '{term}'...")
        term_count = 0
        
        # Request all potentially relevant fields
        params = {
            'search_terms': term,
            'page_size': 50,  # Max per page
            'json': 1,
            'lc': 'en',  # Prefer English
            'fields': 'code,product_name,product_name_en,ingredients_text,ingredients_text_en,allergens,allergens_tags,allergens_from_ingredients'
        }
        
        data = fetch_with_retry(API_URL, params)
        if not data:
            continue
        
        items = data.get('products', [])
        
        for p in items:
            if len(products) >= target_count:
                break
                
            if require_allergens and term_count >= MAX_ITEMS_PER_KEYWORD:
                print(f"      -> Hit quota for '{term}'. Moving to next term.")
                break
            
            # Validate product
            is_valid, product_data = validate_product_data(p)
            
            if not is_valid or not product_data:
                continue
            
            # Check for duplicates
            if product_data['id'] in collected_ids:
                continue
            
            # Apply allergen filter
            has_allergens = product_data['allergens'] != "EMPTY"
            
            if require_allergens:
                if not has_allergens:
                    continue
                if not matches_target_allergens(product_data['allergens']):
                    continue
            else:
                if has_allergens:
                    continue
            
            # Add to collection
            products.append(product_data)
            collected_ids.add(product_data['id'])
            term_count += 1
            
            print(f"     + [{len(products)}/{target_count}] {product_data['name'][:30]}...")
            print(f"        Ingredients: {product_data['ingredients'][:50]}...")
            if has_allergens:
                print(f"        Allergens: {product_data['allergens']}")
        
        # Be respectful to API
        time.sleep(1.5)
    
    return products

def save_to_file(list_with, list_without):
    """
    Save collected data to file in SPECIFIC ORDER:
    1. First 50: Safe Products (No Allergens)
    2. Next 150: Allergen Products (With Allergens)
    
    This ensures the 'Last 10' data points (191-200) contain allergens
    for the lab demonstration requirement.
    """
    print(f"\n--- Saving Data to {OUTPUT_FILE} ---")
    try:
        with open(OUTPUT_FILE, 'w', encoding='utf-8') as f:
            # Write header
            f.write("id;name;link;ingredients;allergensraw\n")
            
            # 1. WRITE SAFE PRODUCTS FIRST (IDs 1-50)
            print("   > Writing 50 SAFE products first...")
            for i, p in enumerate(list_without, 1):
                f.write(f"{i};{p['name']};{p['link']};{p['ingredients']};{p['allergens']}\n")
            
            # 2. WRITE ALLERGEN PRODUCTS SECOND (IDs 51-200)
            # Start counting from where the safe list ended (len(list_without) + 1)
            print("   > Writing 150 ALLERGEN products next...")
            start_id = len(list_without) + 1
            for i, p in enumerate(list_with, start_id):
                f.write(f"{i};{p['name']};{p['link']};{p['ingredients']};{p['allergens']}\n")
        
        print(f"  ✅ Success! Saved {len(list_without) + len(list_with)} products.")
        print(f"  👉 IDs 1-{len(list_without)}: Safe Products")
        print(f"  👉 IDs {len(list_without)+1}-{len(list_without)+len(list_with)}: Allergen Products")
        
        # Show statistics
        print(f"\n📊 Statistics:")
        print(f"  Safe Products: {len(list_without)}/{TARGET_NO_ALLERGENS}")
        print(f"  Allergen Products: {len(list_with)}/{TARGET_ALLERGENS}")
            
    except IOError as e:
        print(f"  ❌ Error writing file: {e}")
    except Exception as e:
        print(f"  ❌ Unexpected error: {e}")

def main():
    """
    Main execution function
    """
    print("="*70)
    print("  OPEN FOOD FACTS COLLECTOR - DEMO OPTIMIZED")
    print("="*70)
    
    # Initialize tracking
    global_ids = set()
    
    # Search terms - broad categories
    allergen_keywords = [
        "milk", "cheese", "yogurt", "butter", "cream",
        "egg", "mayonnaise", "omelette",
        "peanut", "peanut butter",
        "nuts", "almond", "cashew", "hazelnut", "walnut",
        "bread", "pasta", "cereal", "wheat", "flour",
        "soy", "tofu", "soy sauce",
        "fish", "salmon", "tuna", "sardines",
        "shrimp", "crab", "lobster", "shellfish",
        "sesame", "sesame oil", "tahini"
    ]
    
    safe_keywords = [
        "rice", "basmati rice", "brown rice",
        "salt", "sea salt", "himalayan salt",
        "sugar", "brown sugar", "cane sugar",
        "honey", "maple syrup",
        "olive oil", "coconut oil", "vegetable oil",
        "tea", "black tea", "green tea", "herbal tea",
        "coffee", "ground coffee", "coffee beans",
        "vinegar", "apple cider vinegar", "balsamic vinegar",
        "fruits", "apple", "banana", "orange", "berries",
        "vegetables", "carrot", "potato", "tomato", "onion",
        "water", "sparkling water", "mineral water"
    ]
    
    # Step 1: Fetch products WITH allergens
    print("\n🔍 COLLECTING PRODUCTS WITH ALLERGENS")
    list_allergens = fetch_products_batch(
        allergen_keywords, 
        TARGET_ALLERGENS, 
        True, 
        global_ids
    )
    
    # Fallback search if we need more
    if len(list_allergens) < TARGET_ALLERGENS:
        print(f"\n   ⚠️ Need {TARGET_ALLERGENS - len(list_allergens)} more allergen products...")
        extra_terms = [
            "chocolate", "cookies", "biscuits", "cake", "pastry",
            "sausage", "bacon", "ham", "deli meat",
            "sauce", "dressing", "condiment",
            "snack", "chips", "crackers", "pretzels"
        ]
        additional = fetch_products_batch(
            extra_terms, 
            TARGET_ALLERGENS - len(list_allergens), 
            True, 
            global_ids
        )
        list_allergens.extend(additional)
    
    # Step 2: Fetch products WITHOUT allergens
    print("\n🔍 COLLECTING SAFE PRODUCTS (NO ALLERGENS)")
    list_safe = fetch_products_batch(
        safe_keywords, 
        TARGET_NO_ALLERGENS, 
        False, 
        global_ids
    )
    
    # Fallback for safe products
    if len(list_safe) < TARGET_NO_ALLERGENS:
        print(f"\n   ⚠️ Need {TARGET_NO_ALLERGENS - len(list_safe)} more safe products...")
        extra_safe = [
            "herbs", "spices", "pepper", "cinnamon", "ginger",
            "beans", "lentils", "chickpeas",
            "mushrooms", "garlic", "ginger",
            "lemon", "lime", "grapefruit"
        ]
        additional_safe = fetch_products_batch(
            extra_safe,
            TARGET_NO_ALLERGENS - len(list_safe),
            False,
            global_ids
        )
        list_safe.extend(additional_safe)
    
    # Step 3: Save results
    # Function will handle the ordering: Safe first, then Allergens
    save_to_file(list_allergens, list_safe)
    
    # Final summary
    print(f"\n🎯 MISSION COMPLETE!")
    print(f"  Total Products Collected: {len(list_allergens) + len(list_safe)}")
    print(f"  Output File: {OUTPUT_FILE}")
    
    # Quality check
    print(f"\n✅ QUALITY CHECK:")
    if list_allergens:
        print(f"  Average ingredient length (Allergen): {sum(len(p['ingredients']) for p in list_allergens) / len(list_allergens):.0f} chars")
    if list_safe:
        print(f"  Average ingredient length (Safe): {sum(len(p['ingredients']) for p in list_safe) / len(list_safe):.0f} chars")

if __name__ == "__main__":
    main()